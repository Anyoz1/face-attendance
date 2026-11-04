from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
import sqlite3
import uuid


def now():
    return datetime.now(timezone.utc).isoformat()


class Store:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS people (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, group_name TEXT NOT NULL,
                    created_at TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS templates (
                    person_id TEXT NOT NULL REFERENCES people(id), model TEXT NOT NULL,
                    embedding BLOB NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, group_name TEXT NOT NULL,
                    opened_at TEXT NOT NULL, closed_at TEXT
                );
                CREATE UNIQUE INDEX IF NOT EXISTS single_active_session
                    ON sessions((1)) WHERE closed_at IS NULL;
                CREATE TABLE IF NOT EXISTS attendance (
                    session_id TEXT NOT NULL REFERENCES sessions(id),
                    person_id TEXT NOT NULL REFERENCES people(id), name TEXT NOT NULL,
                    present_at TEXT, method TEXT, PRIMARY KEY(session_id, person_id)
                );
                CREATE TABLE IF NOT EXISTS enrollments (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, group_name TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS audit (
                    at TEXT NOT NULL, session_id TEXT NOT NULL,
                    person_id TEXT NOT NULL, action TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS groups (
                    name TEXT PRIMARY KEY, created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS session_groups (
                    session_id TEXT NOT NULL REFERENCES sessions(id), group_name TEXT NOT NULL,
                    PRIMARY KEY(session_id, group_name)
                );
            """)
            columns = {row['name'] for row in db.execute('PRAGMA table_info(enrollments)')}
            if 'person_id' not in columns:
                db.execute('ALTER TABLE enrollments ADD COLUMN person_id TEXT')
            columns = {row['name'] for row in db.execute('PRAGMA table_info(attendance)')}
            if 'group_name' not in columns:
                db.execute('ALTER TABLE attendance ADD COLUMN group_name TEXT')
            # Old sessions had exactly one group. Preserve that historical value,
            # even when a person has since moved to another group.
            db.execute('''UPDATE attendance SET group_name=(
                SELECT group_name FROM sessions WHERE id=attendance.session_id
            ) WHERE group_name IS NULL''')
            db.execute('''INSERT INTO session_groups(session_id,group_name)
                SELECT id,group_name FROM sessions s WHERE NOT EXISTS (
                    SELECT 1 FROM session_groups sg WHERE sg.session_id=s.id
                )''')
            db.execute('''INSERT OR IGNORE INTO groups(name,created_at)
                SELECT group_name,? FROM people UNION SELECT group_name,? FROM session_groups''', (now(), now()))
            db.execute('INSERT OR IGNORE INTO groups VALUES (?,?)', ('Основная', now()))

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    def people(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("""
                SELECT p.*, (SELECT count(*) FROM templates t WHERE t.person_id=p.id) samples
                FROM people p WHERE archived=0 ORDER BY group_name, name
            """)]

    def groups(self):
        with self.connect() as db:
            return [dict(row) for row in db.execute('''
                SELECT g.name,count(p.id) people FROM groups g LEFT JOIN people p
                ON p.group_name=g.name AND p.archived=0 GROUP BY g.name ORDER BY g.name
            ''')]

    def create_group(self, name):
        name = name.strip()
        if not name or len(name) > 80:
            raise ValueError('Название группы должно содержать от 1 до 80 символов')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM groups WHERE name=?', (name,)).fetchone():
                raise ValueError('Такая группа уже есть')
            db.execute('INSERT INTO groups VALUES (?,?)', (name, now()))
        return {'name': name, 'people': 0}

    def move_person(self, person_id, group):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not db.execute('SELECT 1 FROM groups WHERE name=?', (group,)).fetchone():
                raise ValueError('Группа не найдена')
            person = db.execute('SELECT name FROM people WHERE id=? AND archived=0', (person_id,)).fetchone()
            if not person:
                raise ValueError('Человек не найден')
            if db.execute('SELECT 1 FROM people WHERE group_name=? AND name=? AND id<>? AND archived=0',
                          (group, person['name'], person_id)).fetchone():
                raise ValueError('В этой группе уже есть человек с таким именем')
            db.execute('UPDATE people SET group_name=? WHERE id=?', (group, person_id))

    def add_person(self, name, group, embeddings, model):
        person_id = uuid.uuid4().hex
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO groups VALUES (?,?)', (group, now()))
            db.execute("INSERT INTO people(id,name,group_name,created_at) VALUES (?,?,?,?)",
                       (person_id, name, group, now()))
            db.executemany("INSERT INTO templates VALUES (?,?,?)",
                           [(person_id, model, e.astype('float32').tobytes()) for e in embeddings])
        return person_id

    def gallery(self, model):
        with self.connect() as db:
            return [dict(r) for r in db.execute("""
                SELECT p.id,p.name,t.embedding FROM templates t JOIN people p
                ON p.id=t.person_id WHERE t.model=? AND (p.archived=0 OR EXISTS (
                    SELECT 1 FROM attendance a JOIN sessions s ON s.id=a.session_id
                    WHERE a.person_id=p.id AND s.closed_at IS NULL
                ))
            """, (model,))]

    def archive(self, person_id):
        with self.connect() as db:
            return db.execute("UPDATE people SET archived=1 WHERE id=? AND archived=0",
                              (person_id,)).rowcount > 0

    def pending(self):
        with self.connect() as db:
            row = db.execute("SELECT * FROM enrollments LIMIT 1").fetchone()
            return dict(row) if row else None

    def set_pending(self, name, group, timestamp, person_id=None):
        enrollment = dict(id=uuid.uuid4().hex, name=name, group_name=group, created_at=timestamp, person_id=person_id)
        with self.connect() as db:
            db.execute("DELETE FROM enrollments")
            db.execute('INSERT OR IGNORE INTO groups VALUES (?,?)', (group, now()))
            db.execute("INSERT INTO enrollments(id,name,group_name,created_at,person_id) VALUES (:id,:name,:group_name,:created_at,:person_id)", enrollment)
        return enrollment

    def complete_enrollment(self, enrollment_id, embeddings, model):
        """Replace a profile only after capture succeeds; preserve person ID and history."""
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            enrollment = db.execute('SELECT * FROM enrollments WHERE id=?', (enrollment_id,)).fetchone()
            if not enrollment:
                raise ValueError('Регистрация уже отменена')
            person_id = enrollment['person_id'] or uuid.uuid4().hex
            if enrollment['person_id']:
                person = db.execute('SELECT id FROM people WHERE id=? AND archived=0', (person_id,)).fetchone()
                if not person:
                    raise ValueError('Запись человека больше недоступна')
                db.execute('DELETE FROM templates WHERE person_id=?', (person_id,))
            else:
                db.execute('INSERT INTO people(id,name,group_name,created_at) VALUES (?,?,?,?)',
                           (person_id, enrollment['name'], enrollment['group_name'], now()))
            db.executemany('INSERT INTO templates VALUES (?,?,?)',
                           [(person_id, model, embedding.astype('float32').tobytes()) for embedding in embeddings])
            db.execute('DELETE FROM enrollments WHERE id=?', (enrollment_id,))
        return person_id

    def cancel_pending(self):
        with self.connect() as db:
            db.execute("DELETE FROM enrollments")

    @staticmethod
    def session_data(db, row):
        if row is None:
            return None
        groups = [item['group_name'] for item in db.execute(
            'SELECT group_name FROM session_groups WHERE session_id=? ORDER BY group_name', (row['id'],))]
        return {**dict(row), 'group_names': groups}

    def active(self):
        with self.connect() as db:
            row = db.execute("SELECT * FROM sessions WHERE closed_at IS NULL").fetchone()
            return self.session_data(db, row)

    def start(self, name, group):
        session_id = uuid.uuid4().hex
        groups = [group] if isinstance(group, str) else group
        if not groups or any(not isinstance(item, str) or not item.strip() or len(item.strip()) > 80 for item in groups):
            raise ValueError('Выберите хотя бы одну группу')
        groups = sorted(set(item.strip() for item in groups))
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM sessions WHERE closed_at IS NULL").fetchone():
                raise ValueError("Сначала завершите текущую проверку")
            placeholders = ','.join('?' for _ in groups)
            people = db.execute(f'SELECT id,name,group_name FROM people WHERE group_name IN ({placeholders}) AND archived=0', groups).fetchall()
            populated = {person['group_name'] for person in people}
            empty = [item for item in groups if item not in populated]
            if empty:
                raise ValueError('Нет зарегистрированных людей в группах: ' + ', '.join(empty))
            db.execute("INSERT INTO sessions VALUES (?,?,?,?,NULL)", (session_id, name, ' + '.join(groups), now()))
            db.executemany('INSERT INTO session_groups VALUES (?,?)', [(session_id, item) for item in groups])
            db.executemany("INSERT INTO attendance(session_id,person_id,name,group_name) VALUES (?,?,?,?)",
                           [(session_id, p['id'], p['name'], p['group_name']) for p in people])
        return session_id

    def close(self, session_id):
        with self.connect() as db:
            return db.execute("UPDATE sessions SET closed_at=? WHERE id=? AND closed_at IS NULL",
                              (now(), session_id)).rowcount > 0

    def mark(self, session_id, person_id, present=True, method="auto"):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            session = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
            if not session:
                raise ValueError("Проверка не найдена")
            if session['closed_at']:
                raise ValueError("Проверка уже завершена")
            row = db.execute("SELECT * FROM attendance WHERE session_id=? AND person_id=?",
                             (session_id, person_id)).fetchone()
            if not row:
                raise ValueError("Человека нет в списке этой проверки")
            if method == "auto" and row['present_at']:
                return False
            db.execute("UPDATE attendance SET present_at=?,method=? WHERE session_id=? AND person_id=?",
                       (now() if present else None, method if present else None, session_id, person_id))
            db.execute("INSERT INTO audit VALUES (?,?,?,?)",
                       (now(), session_id, person_id, f"{method}:{'present' if present else 'clear'}"))
        return True

    def sessions(self):
        with self.connect() as db:
            return [self.session_data(db, r) for r in db.execute("""
                SELECT s.*,count(a.person_id) total,
                       count(a.present_at) present
                FROM sessions s LEFT JOIN attendance a ON a.session_id=s.id
                GROUP BY s.id ORDER BY s.opened_at DESC
            """)]

    def report(self, session_id):
        with self.connect() as db:
            session = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
            if not session:
                return None
            details = self.session_data(db, session)
            rows = [dict(r) for r in db.execute("""
                SELECT person_id,name,group_name,present_at,method FROM attendance
                WHERE session_id=? ORDER BY group_name,name
            """, (session_id,))]
        summaries = []
        for group in details['group_names']:
            members = [person for person in rows if person['group_name'] == group]
            present = sum(bool(person['present_at']) for person in members)
            summaries.append({'name': group, 'total': len(members), 'present': present,
                              'unmarked': len(members) - present})
        return {**details, 'people': rows, 'groups': summaries, 'total': len(rows),
                'present': sum(bool(r['present_at']) for r in rows)}
