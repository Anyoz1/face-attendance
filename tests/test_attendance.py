import csv
import io
import json
import os
from pathlib import Path
import time

import numpy as np
import pytest
from fastapi.testclient import TestClient

from attendance.app import create_app
from attendance.store import Store
from attendance.vision import Face, FrameError, SFACE_MODEL_ID as MODEL_ID, Vision, match


def vector(index):
    result = np.zeros(128, np.float32)
    result[index] = 1
    return result


class FakeVision:
    ready = True
    model_id = MODEL_ID
    embedding_size = 128
    display_name = 'SFace'

    def analyze(self, data):
        if data.startswith(b'bad'):
            raise FrameError('Посмотрите в камеру')
        index = {b'A': 0, b'B': 1, b'C': 2}.get(data[:1], 1)
        return Face(vector(index), [.2, .1, .4, .5], 100, 0)


class Clock:
    def __init__(self):
        self.value = 1000.

    def __call__(self):
        return self.value

    def advance(self, seconds):
        self.value += seconds


@pytest.fixture
def environment(tmp_path):
    app = create_app(tmp_path, FakeVision(), clock=Clock())
    with TestClient(app) as admin:
        assert admin.get('/admin').status_code == 200
        tokens = json.loads((tmp_path / 'tokens.json').read_text())
        with TestClient(app) as terminal:
            assert terminal.get('/terminal', params={'pair': tokens['terminal']}).status_code == 200
            yield app, admin, terminal


def frame(app, client, content):
    app.state.runtime.clock.advance(.35)
    response = client.post('/api/frames', content=content, headers={'Content-Type':'image/jpeg'})
    assert response.status_code == 200, response.text
    return response.json()


def start(admin, group='Основная'):
    response = admin.post('/api/sessions', json={'name':'Утро', 'group_name':group})
    assert response.status_code == 200, response.text
    return response.json()['id']


def test_enrollment_uses_phone_and_persists_six_samples(environment):
    app, admin, phone = environment
    assert admin.post('/api/enrollments', json={'name':'Алия', 'group_name':'Группа 1'}).status_code == 200
    for number in range(6):
        result = frame(app, phone, f'A{number}'.encode())
        if number < 5:
            assert result['status'] == 'enrolling'
            assert app.state.runtime.store.people() == []
    assert result['status'] == 'enrolled'
    people = admin.get('/api/dashboard').json()['people']
    assert [(p['name'],p['group_name'],p['samples']) for p in people] == [('Алия','Группа 1',6)]
    assert app.state.runtime.store.pending() is None
    assert Store(app.state.runtime.store.path).people()[0]['samples'] == 6


def test_three_frames_duplicate_protection_and_closed_report(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    a = store.add_person('Алия','Основная',[vector(0)] * 6,MODEL_ID)
    store.add_person('Бек','Основная',[vector(1)] * 6,MODEL_ID)
    session = start(admin)
    assert frame(app, phone, b'A1')['status'] == 'recognizing'
    assert frame(app, phone, b'A2')['status'] == 'recognizing'
    assert frame(app, phone, b'A3')['status'] == 'present'
    assert frame(app, phone, b'A4')['status'] == 'already'
    result = store.report(session)
    assert result['present'] == 1 and result['total'] == 2
    timestamp = next(p['present_at'] for p in result['people'] if p['person_id'] == a)
    assert admin.post(f'/api/sessions/{session}/close').status_code == 200
    assert frame(app, phone, b'A5')['status'] == 'idle'
    assert store.report(session)['people'][0]['present_at'] == timestamp
    exported = admin.get(f'/api/sessions/{session}/export.csv')
    assert 'Присутствует' in exported.text and 'Отсутствует' in exported.text
    assert admin.post(f'/api/sessions/{session}/people/{a}',json={'present':False}).status_code == 409


def test_identity_bad_frame_gap_and_replay_reset_confirmation(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    store.add_person('Алия','Основная',[vector(0)] * 6,MODEL_ID)
    store.add_person('Бек','Основная',[vector(1)] * 6,MODEL_ID)
    session = start(admin)
    frame(app, phone, b'A1'); frame(app, phone, b'A2')
    assert frame(app, phone, b'B1')['status'] == 'recognizing'
    frame(app, phone, b'bad1')
    assert frame(app, phone, b'B2')['status'] == 'recognizing'
    assert frame(app, phone, b'B2')['status'] == 'waiting'
    frame(app, phone, b'B3')
    for track in app.state.runtime.tracks.values():
        track['at'] = app.state.runtime.clock() - 3
    assert frame(app, phone, b'B4')['status'] == 'recognizing'
    assert store.report(session)['present'] == 0


def test_sessions_snapshot_manual_mark_and_history_survive_archiving(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    person = store.add_person('Алия','Основная',[vector(0)] * 6,MODEL_ID)
    session = start(admin)
    assert admin.post('/api/sessions',json={'name':'Другая','group_name':'Основная'}).status_code == 409
    late = store.add_person('Бек','Основная',[vector(1)] * 6,MODEL_ID)
    assert store.report(session)['total'] == 1
    assert admin.post(f'/api/sessions/{session}/people/{late}',json={'present':True}).status_code == 409
    for n in range(3):
        result = frame(app,phone,f'B{n}'.encode())
    assert result['status'] == 'other_group'
    assert admin.post(f'/api/sessions/{session}/people/{person}',json={'present':True}).status_code == 200
    report = store.report(session)
    assert report['present'] == 1 and report['people'][0]['method'] == 'manual'
    assert admin.post(f'/api/people/{person}/archive').status_code == 200
    assert person in {entry['id'] for entry in store.gallery(MODEL_ID)}
    admin.post(f'/api/sessions/{session}/close')
    assert person not in {entry['id'] for entry in store.gallery(MODEL_ID)}
    assert store.report(session)['people'][0]['name'] == 'Алия'
    next_session = start(admin)
    assert store.report(next_session)['total'] == 1
    assert store.report(next_session)['people'][0]['person_id'] == late


def test_auth_permissions_csrf_and_upload_limit(environment):
    app, admin, phone = environment
    with TestClient(app, base_url='https://laptop.example', client=('192.168.1.50',1234)) as stranger:
        assert stranger.get('/admin').status_code == 403
        assert stranger.get('/api/dashboard').status_code == 403
        assert stranger.get('/api/terminal').status_code == 403
        assert stranger.post('/api/frames',content=b'x').status_code == 403
    assert phone.get('/api/dashboard').status_code == 403
    assert phone.get('/api/pairing').status_code == 403
    assert phone.post('/api/enrollments',json={'name':'Чужой'}).status_code == 403
    assert admin.post('/api/enrollments',json={'name':'Имя'},headers={'Origin':'https://evil.example'}).status_code == 403
    assert phone.post('/api/frames',content=b'x'*1_500_001).status_code == 413
    assert admin.get('/api/pairing/qr.png').headers['content-type'] == 'image/png'
    assert admin.get('/api/dashboard').headers['cache-control'] == 'no-store'


def test_manual_clear_csv_formula_and_new_session_resets(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    person = store.add_person('=SUM(1)','Основная',[vector(0)] * 6,MODEL_ID)
    session = start(admin)
    frame(app,phone,b'A1'); frame(app,phone,b'A2')
    admin.post(f'/api/sessions/{session}/close')
    next_session = start(admin)
    assert frame(app,phone,b'A3')['status'] == 'recognizing'
    assert store.report(next_session)['present'] == 0
    admin.post(f'/api/sessions/{next_session}/people/{person}',json={'present':True})
    admin.post(f'/api/sessions/{next_session}/people/{person}',json={'present':False})
    assert store.report(next_session)['present'] == 0
    exported = admin.get(f'/api/sessions/{session}/export.csv').text.lstrip('\ufeff')
    rows = list(csv.reader(io.StringIO(exported),delimiter=';'))
    assert rows[1][0] == "'=SUM(1)"
    assert rows[1][2] == 'Отсутствует'


def test_matching_margin_is_between_people_not_templates():
    gallery = [{'id':'a','name':'A','embedding':vector(0).tobytes()} for _ in range(6)]
    gallery.append({'id':'b','name':'B','embedding':vector(1).tobytes()})
    assert match(vector(0),gallery)['id'] == 'a'
    gallery.append({'id':'c','name':'C','embedding':vector(0).tobytes()})
    assert match(vector(0),gallery) is None
    assert match(vector(2),gallery) is None


def test_enrollment_switching_identity_cancellation_and_expiry(environment):
    app, admin, phone = environment
    admin.post('/api/enrollments',json={'name':'Алия'})
    frame(app,phone,b'A1'); frame(app,phone,b'A2')
    assert frame(app,phone,b'B1')['status'] == 'adjust'
    assert len(app.state.runtime.samples) == 0
    admin.delete('/api/enrollments')
    assert frame(app,phone,b'A3')['status'] == 'idle'
    app.state.runtime.store.set_pending('Истекшая','Основная',time.time()-301)
    assert phone.get('/api/terminal').json()['enrollment'] is None
    assert app.state.runtime.store.people() == []


def test_duplicate_face_is_blocked_and_recapture_preserves_history(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    person = store.add_person('Алия','Основная',[vector(0)] * 6,MODEL_ID)
    session = start(admin)
    admin.post(f'/api/sessions/{session}/people/{person}',json={'present':True})
    admin.post(f'/api/sessions/{session}/close')
    admin.post('/api/enrollments',json={'name':'Другое имя'})
    assert frame(app,phone,b'A-new')['status'] == 'enrollment_conflict'
    assert len(store.people()) == 1
    assert admin.get('/api/dashboard').json()['enrollment_warning']
    admin.delete('/api/enrollments')
    assert admin.post(f'/api/people/{person}/enrollment').status_code == 200
    for index in range(3):
        frame(app,phone,f'A-cancel-{index}'.encode())
    admin.delete('/api/enrollments')
    assert len(store.gallery(MODEL_ID)) == 6
    assert admin.post(f'/api/people/{person}/enrollment').status_code == 200
    for index in range(6):
        result = frame(app,phone,f'B-reshoot-{index}'.encode())
    assert result['status'] == 'enrolled' and result['person_id'] == person
    assert len(store.people()) == 1
    assert store.report(session)['people'][0]['present_at']
    assert np.frombuffer(store.gallery(MODEL_ID)[0]['embedding'],np.float32)[1] == 1


def test_audit_is_admin_only_and_reports_profiles_needing_capture(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    store.add_person('Нет лица','Основная',[],MODEL_ID)
    store.add_person('Мало кадров','Основная',[vector(0)],MODEL_ID)
    store.add_person('Похожий','Основная',[vector(0)] * 6,MODEL_ID)
    response = admin.get('/api/recognition/audit')
    assert response.status_code == 200
    audit = response.json()
    assert len(audit['unusable']) == 1 and len(audit['insufficient']) == 1
    assert len(audit['similar']) == 1
    assert phone.get('/api/recognition/audit').status_code == 403
    person_id = store.people()[0]['id']
    assert phone.post(f'/api/people/{person_id}/enrollment').status_code == 403


def test_multiple_groups_recognized_in_one_session_with_separate_totals(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    first = store.add_person('Алия','ИС-201',[vector(0)] * 6,MODEL_ID)
    second = store.add_person('Бек','ИС-202',[vector(1)] * 6,MODEL_ID)
    store.add_person('Не пришёл','ИС-202',[vector(3)] * 6,MODEL_ID)
    store.add_person('Другая группа','ИС-203',[vector(2)] * 6,MODEL_ID)
    response = admin.post('/api/sessions',json={'name':'Две группы','group_names':['ИС-202','ИС-201','ИС-201']})
    assert response.status_code == 200, response.text
    report = response.json()
    session_id = report['id']
    assert report['group_names'] == ['ИС-201','ИС-202'] and report['total'] == 3
    assert phone.get('/api/terminal').json()['active']['group_names'] == ['ИС-201','ИС-202']
    for prefix, name in [('A','Алия'),('B','Бек')]:
        for index in range(3):
            result = frame(app,phone,f'{prefix}-two-groups-{index}'.encode())
        assert result['status'] == 'present' and result['name'] == name
    for index in range(3):
        result = frame(app,phone,f'C-other-group-{index}'.encode())
    assert result['status'] == 'other_group'
    assert frame(app,phone,b'B-repeat-1')['status'] == 'recognizing'
    for index in range(3):
        result = frame(app,phone,f'B-repeat-{index+2}'.encode())
    assert result['status'] == 'already'
    report = admin.get(f'/api/sessions/{session_id}').json()
    assert report['present'] == 2
    summaries = {group['name']:group for group in report['groups']}
    assert summaries['ИС-201'] == {'name':'ИС-201','total':1,'present':1,'unmarked':0}
    assert summaries['ИС-202'] == {'name':'ИС-202','total':2,'present':1,'unmarked':1}
    assert {person['person_id']:person['group_name'] for person in report['people']}[second] == 'ИС-202'
    assert admin.post('/api/sessions',json={'name':'Ещё','group_names':['ИС-203']}).status_code == 409
    admin.post(f'/api/sessions/{session_id}/close')
    exported = admin.get(f'/api/sessions/{session_id}/export.csv').text.lstrip('\ufeff')
    rows = list(csv.reader(io.StringIO(exported),delimiter=';'))
    assert len(rows) == 4
    assert any(row[:3] == ['Алия','ИС-201','Присутствует'] for row in rows)
    assert any(row[:3] == ['Не пришёл','ИС-202','Отсутствует'] for row in rows)
    filtered = admin.get(f'/api/sessions/{session_id}/export.csv',params={'group_name':'ИС-202'})
    filtered_rows = list(csv.reader(io.StringIO(filtered.text.lstrip('\ufeff')),delimiter=';'))
    assert len(filtered_rows) == 3 and all(row[1] == 'ИС-202' for row in filtered_rows[1:])
    assert admin.get(f'/api/sessions/{session_id}/export.csv',params={'group_name':'ИС-203'}).status_code == 400


def test_explicit_empty_groups_creation_persistence_and_permissions(environment):
    app, admin, phone = environment
    response = admin.post('/api/groups',json={'name':'  Новая группа  '})
    assert response.status_code == 200 and response.json() == {'name':'Новая группа','people':0}
    assert {'name':'Новая группа','people':0} in admin.get('/api/groups').json()['groups']
    assert {'name':'Новая группа','people':0} in Store(app.state.runtime.store.path).groups()
    assert admin.post('/api/groups',json={'name':'Новая группа'}).status_code == 409
    assert admin.post('/api/groups',json={'name':'   '}).status_code == 409
    assert phone.get('/api/groups').status_code == 403
    assert phone.post('/api/groups',json={'name':'Чужая группа'}).status_code == 403
    assert admin.post('/api/sessions',json={'name':'Пустая','group_names':['Новая группа']}).status_code == 409
    assert app.state.runtime.store.active() is None


def test_group_move_keeps_current_and_historical_rosters(environment):
    app, admin, phone = environment
    store = app.state.runtime.store
    person = store.add_person('Алия','Старая',[vector(0)] * 6,MODEL_ID)
    admin.post('/api/groups',json={'name':'Новая'})
    session_id = start(admin,'Старая')
    assert phone.post(f'/api/people/{person}/group',json={'group_name':'Новая'}).status_code == 403
    assert admin.post(f'/api/people/{person}/group',json={'group_name':'Новая'}).status_code == 200
    assert store.people()[0]['group_name'] == 'Новая'
    for index in range(3):
        result = frame(app,phone,f'A-after-moving-{index}'.encode())
    assert result['status'] == 'present'
    report = store.report(session_id)
    assert report['people'][0]['group_name'] == 'Старая' and report['present'] == 1
    admin.post(f'/api/sessions/{session_id}/close')
    next_session = start(admin,'Новая')
    assert store.report(next_session)['people'][0]['group_name'] == 'Новая'
    assert store.report(session_id)['group_names'] == ['Старая']
    assert 'Алия;Старая;Присутствует' in admin.get(f'/api/sessions/{session_id}/export.csv').text


@pytest.mark.parametrize('body,code', [
    ({'name':'Тест','group_names':[]},422),
    ({'name':'Тест','group_names':['   ']},409),
    ({'name':'Тест','group_names':['Неизвестная']},409),
    ({'name':'Тест','group_names':['Основная'],'group_name':'Основная'},400),
    ({'name':'Тест'},400),
])
def test_invalid_group_selection_does_not_start_partial_session(environment, body, code):
    app, admin, phone = environment
    app.state.runtime.store.add_person('Алия','Основная',[vector(0)] * 6,MODEL_ID)
    assert admin.post('/api/sessions',json=body).status_code == code
    assert app.state.runtime.store.active() is None


def test_old_session_migration_preserves_historical_group_and_is_idempotent(tmp_path):
    import sqlite3
    database = tmp_path / 'legacy.sqlite3'
    with sqlite3.connect(database) as db:
        db.executescript('''
            CREATE TABLE people(id TEXT PRIMARY KEY,name TEXT NOT NULL,group_name TEXT NOT NULL,
                created_at TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE sessions(id TEXT PRIMARY KEY,name TEXT NOT NULL,group_name TEXT NOT NULL,
                opened_at TEXT NOT NULL,closed_at TEXT);
            CREATE TABLE attendance(session_id TEXT NOT NULL,person_id TEXT NOT NULL,name TEXT NOT NULL,
                present_at TEXT,method TEXT,PRIMARY KEY(session_id,person_id));
            INSERT INTO people VALUES ('person','Алия','Новая','2026-11-01',0);
            INSERT INTO sessions VALUES ('old','Старая проверка','Старая','2026-11-01','2026-11-01');
            INSERT INTO attendance VALUES ('old','person','Алия','2026-11-01','manual');
        ''')
    store = Store(database)
    report = store.report('old')
    assert report['group_names'] == ['Старая'] and report['present'] == 1
    assert report['people'][0]['group_name'] == 'Старая'
    store.add_person('Бек','Вторая',[vector(1)] * 6,MODEL_ID)
    session_id = store.start('Общая',['Новая','Вторая'])
    reopened = Store(database)
    assert reopened.active()['group_names'] == ['Вторая','Новая']
    assert reopened.report(session_id)['total'] == 2
    assert 'Вторая + Новая' not in {group['name'] for group in reopened.groups()}
    assert reopened.report('old')['people'][0]['group_name'] == 'Старая'


@pytest.mark.parametrize('recognizer', ['sface','arcface'])
def test_real_models_detect_and_separate_two_faces(pytestconfig, recognizer):
    first_path = pytestconfig.getoption('--face-test-image') or os.environ.get('FACE_TEST_IMAGE')
    other_path = pytestconfig.getoption('--face-test-other-image') or os.environ.get('FACE_TEST_OTHER_IMAGE')
    if not first_path or not other_path:
        pytest.skip('real image paths not supplied')
    vision = Vision(Path(__file__).resolve().parent.parent / 'models', recognizer)
    if not vision.ready:
        pytest.skip(f'{recognizer} model weights not installed')
    first = vision.analyze(Path(first_path).read_bytes())
    repeated = vision.analyze(Path(first_path).read_bytes())
    other = vision.analyze(Path(other_path).read_bytes())
    assert first.embedding.shape == (vision.embedding_size,)
    assert float(first.embedding @ repeated.embedding) > .99
    assert float(first.embedding @ other.embedding) < .5


@pytest.mark.parametrize('recognizer', ['sface','arcface'])
def test_real_registration_to_final_report(tmp_path, pytestconfig, recognizer):
    import cv2
    first_path = pytestconfig.getoption('--face-test-image')
    other_path = pytestconfig.getoption('--face-test-other-image')
    if not first_path or not other_path:
        pytest.skip('real image paths not supplied')
    vision = Vision(Path(__file__).resolve().parent.parent / 'models', recognizer)
    if not vision.ready:
        pytest.skip(f'{recognizer} model weights not installed')
    app = create_app(tmp_path, vision, clock=Clock())
    with TestClient(app) as admin, TestClient(app) as phone:
        admin.get('/admin')
        token = json.loads((tmp_path / 'tokens.json').read_text())['terminal']
        phone.get('/terminal',params={'pair':token})
        for name, path in [('Тест А',first_path),('Тест Б',other_path)]:
            assert admin.post('/api/enrollments',json={'name':name}).status_code == 200
            image = cv2.imread(path)
            for number in range(6):
                augmented = cv2.convertScaleAbs(image, alpha=.97+number*.006, beta=number)
                ok, encoded = cv2.imencode('.jpg',augmented,[cv2.IMWRITE_JPEG_QUALITY,90])
                assert ok
                result = frame(app,phone,encoded.tobytes())
            assert result['status'] == 'enrolled', result
        session = start(admin)
        image = cv2.imread(first_path)
        for number in range(3):
            _, encoded = cv2.imencode('.jpg',image,[cv2.IMWRITE_JPEG_QUALITY,85+number])
            result = frame(app,phone,encoded.tobytes())
        assert result['status'] == 'present' and result['name'] == 'Тест А'
        report = admin.post(f'/api/sessions/{session}/close').json()
        assert report['total'] == 2 and report['present'] == 1
        csv_text = admin.get(f'/api/sessions/{session}/export.csv').text
        assert 'Тест А;Основная;Присутствует' in csv_text
        assert 'Тест Б;Основная;Отсутствует' in csv_text
