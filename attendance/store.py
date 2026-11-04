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
            """)
            columns = {row['name'] for row in db.execute('PRAGMA table_info(enrollments)')}
            if 'person_id' not in columns:
                db.execute('ALTER TABLE enrollments ADD COLUMN person_id TEXT')

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

    def add_person(self, name, group, embeddings, model):
        person_id = uuid.uuid4().hex
        with self.connect() as db:
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

    def active(self):
        with self.connect() as db:
            row = db.execute("SELECT * FROM sessions WHERE closed_at IS NULL").fetchone()
            return dict(row) if row else None

    def start(self, name, group):
        session_id = uuid.uuid4().hex
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM sessions WHERE closed_at IS NULL").fetchone():
                raise ValueError("Сначала завершите текущую проверку")
            people = db.execute("SELECT id,name FROM people WHERE group_name=? AND archived=0", (group,)).fetchall()
            if not people:
                raise ValueError("В этой группе пока нет зарегистрированных людей")
            db.execute("INSERT INTO sessions VALUES (?,?,?,?,NULL)", (session_id, name, group, now()))
            db.executemany("INSERT INTO attendance(session_id,person_id,name) VALUES (?,?,?)",
                           [(session_id, p['id'], p['name']) for p in people])
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
            return [dict(r) for r in db.execute("""
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
            rows = [dict(r) for r in db.execute("""
                SELECT person_id,name,present_at,method FROM attendance
                WHERE session_id=? ORDER BY name
            """, (session_id,))]
        return {**dict(session), 'people': rows, 'total': len(rows),
                'present': sum(bool(r['present_at']) for r in rows)}
