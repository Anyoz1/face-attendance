import csv
import hashlib
import hmac
import io
import json
import logging
import os
from pathlib import Path
import secrets
import threading
import time

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool
import numpy as np
import qrcode

from attendance.store import Store
from attendance.vision import FrameError, MODEL_ID, Vision
from attendance.recognition import Confirmation, ENROLLMENT_SAMPLES, Gallery, Policy

ROOT = Path(__file__).resolve().parent.parent


def local(request):
    return (request.client is not None and request.client.host in ('127.0.0.1', '::1', 'testclient')
            and request.url.hostname in ('127.0.0.1', 'localhost', '::1', 'testserver'))


class PersonInput(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    group_name: str = Field(default='Основная', min_length=1, max_length=80)


class GroupInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)


class MoveInput(BaseModel):
    group_name: str = Field(min_length=1, max_length=80)


class SessionInput(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    group_names: list[str] | None = Field(default=None, min_length=1, max_length=100)
    group_name: str | None = Field(default=None, min_length=1, max_length=80)


class MarkInput(BaseModel):
    present: bool


class Runtime:
    def __init__(self, store, vision, policy=None, clock=None):
        self.store, self.vision = store, vision
        self.policy = policy or Policy()
        self.clock = clock or time.monotonic
        self.lock = threading.RLock()
        self.tracks = {}
        self.enrollment = None
        self.samples = []
        self.enrollment_device = None
        self.enrollment_warning = None
        self.last_seen = 0.
        self.last_result = None

    def pending(self):
        pending = self.store.pending()
        if pending and time.time() - pending['created_at'] > 300:
            self.store.cancel_pending()
            self.samples = []
            self.enrollment_device = None
            self.enrollment_warning = None
            return None
        return pending

    def frame(self, data, device):
        with self.lock:
            timestamp = self.clock()
            self.last_seen = timestamp
            # Bound memory even if a valid paired client invents device identifiers.
            self.tracks = {k: v for k, v in self.tracks.items() if timestamp - v['at'] < 60}
            if device not in self.tracks and len(self.tracks) >= 16:
                self.tracks.pop(next(iter(self.tracks)))
            track = self.tracks.setdefault(device, {'at': 0, 'last_frame_at': 0, 'hashes': [], 'confirmation': Confirmation(self.policy)})
            confirmation = track['confirmation']
            if timestamp - track['last_frame_at'] < .18:
                return {'status': 'waiting', 'message': 'Проверяем…'}
            track['last_frame_at'] = timestamp
            pending = self.pending()
            session = self.store.active()
            context = ('enroll', pending['id']) if pending else ('session', session['id']) if session else ('idle', '')
            if track.get('context') != context or timestamp - track['at'] > 2:
                confirmation.reset()
                track.update(hashes=[], context=context)
                if pending and self.enrollment_device == device:
                    self.samples = []
            track['at'] = timestamp
            if not pending and not session:
                return {'status': 'idle', 'message': 'Ожидаем начала проверки на ноутбуке'}
            digest = hashlib.sha256(data).digest()
            if digest in track['hashes']:
                confirmation.reset()
                return {'status': 'waiting', 'message': 'Ожидаем новый кадр камеры'}
            track['hashes'] = (track['hashes'] + [digest])[-12:]
            try:
                face = self.vision.analyze(data)
            except FrameError as exc:
                confirmation.reset()
                if pending and self.enrollment_device == device:
                    self.samples = []
                return {'status': 'adjust', 'message': str(exc)}

            if pending:
                if self.enrollment != pending['id']:
                    self.enrollment = pending['id']
                    self.samples = []
                    self.enrollment_device = None
                    self.enrollment_warning = None
                if self.enrollment_device is not None and self.enrollment_device != device:
                    return {'status': 'waiting', 'message': 'Регистрация выполняется на другом телефоне'}
                self.enrollment_device = device
                if self.samples and min(float(sample @ face.embedding) for sample in self.samples) < self.policy.enrollment_consistency:
                    self.samples = []
                    return {'status': 'adjust', 'message': 'Лицо изменилось. Начинаем съёмку заново'}
                others = [row for row in self.store.gallery(MODEL_ID) if row['id'] != pending['person_id']]
                duplicate = Gallery(others, len(face.embedding)).duplicate(self.samples + [face.embedding], self.policy)
                if duplicate is not None:
                    self.samples = []
                    self.enrollment_warning = f"Лицо слишком похоже на запись «{duplicate['name']}». Проверьте имя или переснимите существующую запись."
                    return {'status': 'enrollment_conflict', 'message': self.enrollment_warning, 'box': face.box}
                self.enrollment_warning = None
                self.samples.append(face.embedding)
                count = len(self.samples)
                if count >= ENROLLMENT_SAMPLES:
                    try:
                        person_id = self.store.complete_enrollment(pending['id'], self.samples, MODEL_ID)
                    except ValueError as exc:
                        self.samples = []
                        return {'status': 'adjust', 'message': str(exc)}
                    self.samples = []
                    self.enrollment_device = None
                    self.tracks.clear()
                    result = {'status': 'enrolled', 'name': pending['name'], 'person_id': person_id, 'enrollment_id': pending['id'],
                              'message': f"{pending['name']} — {'лицо обновлено' if pending['person_id'] else 'регистрация завершена'}", 'box': face.box}
                    self.last_result = result
                    return result
                return {'status': 'enrolling', 'message': f"{pending['name']}: кадр {count} из {ENROLLMENT_SAMPLES}",
                        'progress': count / ENROLLMENT_SAMPLES, 'box': face.box}

            gallery = Gallery(self.store.gallery(MODEL_ID), len(face.embedding))
            candidate = gallery.identify(face.embedding, self.policy)
            if candidate is None:
                confirmation.reset()
                return {'status': 'unknown', 'message': 'Лицо не узнано. Посмотрите прямо или обратитесь к администратору', 'box': face.box}
            if not confirmation.observe(face.embedding, candidate, gallery, timestamp):
                return {'status': 'recognizing', 'message': 'Держитесь неподвижно — проверяем лицо',
                        'progress': confirmation.progress, 'box': face.box}
            try:
                changed = self.store.mark(session['id'], candidate['id'])
            except ValueError as exc:
                return {'status': 'other_group', 'message': str(exc), 'box': face.box}
            result = {'status': 'present' if changed else 'already', 'name': candidate['name'],
                      'message': f"{candidate['name']} — {'отмечен' if changed else 'уже отмечен'}", 'box': face.box}
            self.last_result = result
            return result


def create_app(data_dir=None, vision=None, clock=None):
    data_dir = Path(data_dir or os.environ.get('FACE_DATA_DIR', ROOT / 'data'))
    data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    secrets_file = data_dir / 'tokens.json'
    if not secrets_file.exists():
        fd = os.open(secrets_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump({'admin': secrets.token_urlsafe(32), 'terminal': secrets.token_urlsafe(32)}, f)
    tokens = json.loads(secrets_file.read_text())
    store = Store(data_dir / 'attendance.sqlite3')
    state = Runtime(store, vision or Vision(ROOT / 'models'), Policy.load(data_dir / 'recognition.json'), clock)
    app = FastAPI(title='Перекличка', docs_url=None, redoc_url=None, openapi_url=None)
    app.state.runtime = state
    app.mount('/static', StaticFiles(directory=ROOT / 'static'), name='static')

    def admin(request: Request):
        value = request.cookies.get('face_admin', '')
        if not hmac.compare_digest(value, tokens['admin']):
            raise HTTPException(403, 'Откройте панель на ноутбуке: http://localhost:8000')

    def terminal(request: Request):
        if not local(request) and request.url.scheme != 'https':
            raise HTTPException(403, 'Телефон должен подключаться по HTTPS')
        if not hmac.compare_digest(request.cookies.get('face_terminal', ''), tokens['terminal']):
            raise HTTPException(403, 'Подключите телефон QR-кодом из панели на ноутбуке')
        device = request.cookies.get('face_device', '')
        if len(device) != 32:
            raise HTTPException(403, 'Подключите телефон заново')
        return device

    @app.middleware('http')
    async def headers(request, call_next):
        if request.method in ('POST', 'DELETE', 'PUT', 'PATCH'):
            origin = request.headers.get('origin')
            if origin and origin != str(request.base_url).rstrip('/'):
                return Response('Недопустимый источник запроса', status_code=403)
        response = await call_next(request)
        response.headers['Cache-Control'] = 'no-store'
        response.headers['Referrer-Policy'] = 'no-referrer'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['X-Frame-Options'] = 'DENY'
        response.headers['Permissions-Policy'] = 'camera=(self), microphone=()'
        response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'"
        return response

    @app.get('/')
    def home(request: Request):
        return RedirectResponse('/admin' if local(request) else '/setup')

    @app.get('/admin')
    def admin_page(request: Request):
        if not local(request):
            admin(request)
        response = FileResponse(ROOT / 'static' / 'admin.html')
        if local(request):
            response.set_cookie('face_admin', tokens['admin'], httponly=True, samesite='strict', secure=request.url.scheme == 'https')
        return response

    @app.get('/terminal')
    def terminal_page(request: Request, pair: str = ''):
        if pair:
            if not hmac.compare_digest(pair, tokens['terminal']):
                raise HTTPException(403, 'Неверная ссылка подключения')
            if not local(request) and request.url.scheme != 'https':
                raise HTTPException(403, 'Используйте HTTPS-ссылку из панели')
            response = RedirectResponse('/terminal', status_code=303)
            secure = request.url.scheme == 'https'
            response.set_cookie('face_terminal', pair, httponly=True, samesite='strict', secure=secure, max_age=86400 * 30)
            response.set_cookie('face_device', secrets.token_hex(16), httponly=True, samesite='strict', secure=secure, max_age=86400 * 30)
            return response
        return FileResponse(ROOT / 'static' / 'terminal.html')

    @app.get('/setup')
    def setup():
        return FileResponse(ROOT / 'static' / 'setup.html')

    @app.get('/ca.crt')
    def certificate():
        cert = data_dir / 'tls' / 'ca.crt'
        if not cert.exists():
            raise HTTPException(404, 'Сертификат ещё не создан')
        return FileResponse(cert, media_type='application/x-x509-ca-cert', filename='Pereklichka-Local-CA.crt')

    @app.get('/api/health')
    def health():
        return {'ok': True, 'models_ready': state.vision.ready, 'model': MODEL_ID, 'liveness': False}

    @app.get('/api/dashboard', dependencies=[Depends(admin)])
    def dashboard():
        with state.lock:
            return {'people': store.people(), 'groups': store.groups(), 'sessions': store.sessions(), 'active': store.active(),
                    'enrollment': state.pending(), 'samples': len(state.samples),
                    'camera_online': state.clock() - state.last_seen < 5,
                    'enrollment_warning': state.enrollment_warning,
                    'last_result': state.last_result, 'models_ready': state.vision.ready}

    def phone_url():
        path = data_dir / 'connection.json'
        info = json.loads(path.read_text()) if path.exists() else {'base_url': 'https://localhost:8443', 'setup_url': 'http://localhost:8000/setup'}
        return {**info, 'terminal_url': info['base_url'] + '/terminal?pair=' + tokens['terminal']}

    @app.get('/api/pairing', dependencies=[Depends(admin)])
    def pairing():
        return phone_url()

    @app.get('/api/pairing/qr.png', dependencies=[Depends(admin)])
    def pairing_qr(kind: str = 'terminal'):
        buf = io.BytesIO()
        qrcode.make(phone_url()['setup_url' if kind == 'setup' else 'terminal_url']).save(buf, format='PNG')
        return Response(buf.getvalue(), media_type='image/png')

    @app.post('/api/enrollments', dependencies=[Depends(admin)])
    def enroll(body: PersonInput):
        name, group = body.name.strip(), body.group_name.strip()
        if not name or not group:
            raise HTTPException(400, 'Заполните имя и группу')
        if not state.vision.ready:
            raise HTTPException(503, 'Сначала установите модели')
        with state.lock:
            if state.pending():
                raise HTTPException(409, 'Завершите или отмените текущую регистрацию')
            if any(p['name'].casefold() == name.casefold() and p['group_name'].casefold() == group.casefold() for p in store.people()):
                raise HTTPException(409, 'Человек с таким именем уже есть в группе')
            state.samples = []
            state.enrollment_warning = None
            state.tracks.clear()
            return store.set_pending(name, group, time.time())

    @app.get('/api/groups', dependencies=[Depends(admin)])
    def groups():
        return {'groups': store.groups()}

    @app.post('/api/groups', dependencies=[Depends(admin)])
    def create_group(body: GroupInput):
        with state.lock:
            try:
                return store.create_group(body.name)
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from exc

    @app.post('/api/people/{person_id}/group', dependencies=[Depends(admin)])
    def move_person(person_id: str, body: MoveInput):
        with state.lock:
            pending = state.pending()
            if pending and pending['person_id'] == person_id:
                raise HTTPException(409, 'Сначала завершите или отмените повторную съёмку')
            try:
                store.move_person(person_id, body.group_name.strip())
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from exc
        return {'ok': True}

    @app.post('/api/people/{person_id}/enrollment', dependencies=[Depends(admin)])
    def recapture(person_id: str):
        with state.lock:
            if state.pending():
                raise HTTPException(409, 'Завершите или отмените текущую регистрацию')
            person = next((person for person in store.people() if person['id'] == person_id), None)
            if person is None:
                raise HTTPException(404, 'Человек не найден')
            if not state.vision.ready:
                raise HTTPException(503, 'Сначала установите модели')
            state.samples = []
            state.enrollment_warning = None
            state.tracks.clear()
            return store.set_pending(person['name'], person['group_name'], time.time(), person_id)

    @app.get('/api/recognition/audit', dependencies=[Depends(admin)])
    def recognition_audit():
        with state.lock:
            gallery = Gallery(store.gallery(MODEL_ID))
            result = gallery.audit(state.policy)
            available = {person['id'] for person in gallery.groups}
            result['unusable'] = [{'id': person['id'], 'name': person['name']} for person in store.people() if person['id'] not in available]
            return result

    @app.delete('/api/enrollments', dependencies=[Depends(admin)])
    def cancel_enrollment():
        with state.lock:
            store.cancel_pending()
            state.samples = []
            state.enrollment_device = None
            state.enrollment_warning = None
            state.tracks.clear()
        return {'ok': True}

    @app.post('/api/people/{person_id}/archive', dependencies=[Depends(admin)])
    def archive(person_id: str):
        if not store.archive(person_id):
            raise HTTPException(404, 'Человек не найден')
        return {'ok': True}

    @app.post('/api/sessions', dependencies=[Depends(admin)])
    def start_session(body: SessionInput):
        name = body.name.strip()
        selected_groups = body.group_names if body.group_names is not None else [body.group_name] if body.group_name else []
        if not name or not selected_groups:
            raise HTTPException(400, 'Заполните название и выберите группы')
        if body.group_names is not None and body.group_name is not None:
            raise HTTPException(400, 'Передайте группы одним списком')
        with state.lock:
            if state.pending():
                raise HTTPException(409, 'Сначала завершите регистрацию человека')
            try:
                session_id = store.start(name, selected_groups)
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from exc
            state.tracks.clear()
        return store.report(session_id)

    @app.get('/api/sessions/{session_id}', dependencies=[Depends(admin)])
    def report(session_id: str):
        result = store.report(session_id)
        if not result:
            raise HTTPException(404, 'Проверка не найдена')
        return result

    @app.post('/api/sessions/{session_id}/close', dependencies=[Depends(admin)])
    def close_session(session_id: str):
        with state.lock:
            if not store.close(session_id):
                raise HTTPException(409, 'Проверка не найдена или уже завершена')
            state.tracks.clear()
        return store.report(session_id)

    @app.post('/api/sessions/{session_id}/people/{person_id}', dependencies=[Depends(admin)])
    def manual_mark(session_id: str, person_id: str, body: MarkInput):
        with state.lock:
            try:
                store.mark(session_id, person_id, body.present, 'manual')
            except ValueError as exc:
                raise HTTPException(409, str(exc)) from exc
        return store.report(session_id)

    @app.get('/api/sessions/{session_id}/export.csv', dependencies=[Depends(admin)])
    def export(session_id: str, group_name: str | None = None):
        result = store.report(session_id)
        if not result:
            raise HTTPException(404, 'Проверка не найдена')
        if group_name is not None and group_name not in result['group_names']:
            raise HTTPException(400, 'Этой группы нет в проверке')
        buf = io.StringIO()
        writer = csv.writer(buf, delimiter=';')
        writer.writerow(['Имя', 'Группа', 'Статус', 'Время (UTC)', 'Способ'])
        def safe_cell(value):
            return "'" + value if value.startswith(('=', '+', '-', '@', '\t', '\r', '\n')) else value
        for row in result['people']:
            if group_name is not None and row['group_name'] != group_name:
                continue
            status = 'Присутствует' if row['present_at'] else 'Отсутствует' if result['closed_at'] else 'Ещё не отмечен'
            writer.writerow([safe_cell(row['name']), safe_cell(row['group_name']), status, row['present_at'] or '', row['method'] or ''])
        return Response(('\ufeff' + buf.getvalue()).encode('utf-8'), media_type='text/csv; charset=utf-8',
                        headers={'Content-Disposition': f'attachment; filename="attendance-{session_id}.csv"'})

    @app.get('/api/terminal')
    def terminal_state(device=Depends(terminal)):
        with state.lock:
            pending = state.pending()
            return {'active': store.active(), 'enrollment': pending,
                    'samples': len(state.samples), 'models_ready': state.vision.ready, 'liveness': False}

    @app.post('/api/frames')
    async def frame(request: Request, device=Depends(terminal)):
        chunks, length = [], 0
        async for chunk in request.stream():
            length += len(chunk)
            if length > 1_500_000:
                raise HTTPException(413, 'Слишком большой кадр')
            chunks.append(chunk)
        try:
            return await run_in_threadpool(state.frame, b''.join(chunks), device)
        except Exception:
            logging.exception('Frame processing failed')
            raise HTTPException(500, 'Не удалось обработать кадр. Проверьте сервер')

    return app
