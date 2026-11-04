from dataclasses import asdict
import json
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from fastapi.testclient import TestClient
import numpy as np
import pytest

from attendance.app import create_app
from attendance.recognition import Gallery, Policy, load_policy, policy_path
from attendance.store import Store
from attendance.vision import ARCFACE_MODEL_ID, ARCFACE_POINTS, Face, FrameError, SFACE_MODEL_ID, Vision, similarity_transform


def embedding(dimension):
    result = np.zeros(dimension, np.float32)
    result[0] = 1
    return result


class FakeArcFace:
    ready = True
    model_id = ARCFACE_MODEL_ID
    embedding_size = 512
    display_name = 'ArcFace ResNet50'

    def analyze(self, data, *, require_single=False):
        return Face(embedding(512), [.2,.2,.5,.5], 100, 0)


class Clock:
    def __init__(self):
        self.value = 1000.

    def __call__(self):
        return self.value


def test_switch_preserves_old_templates_and_history_until_and_after_recapture(tmp_path):
    store = Store(tmp_path / 'attendance.sqlite3')
    person_id = store.add_person('Алия','Группа 1',[embedding(128)] * 6,SFACE_MODEL_ID)
    session_id = store.start('До смены модели','Группа 1')
    store.mark(session_id,person_id)
    store.close(session_id)
    old_vectors = [row['embedding'] for row in store.gallery(SFACE_MODEL_ID)]
    clock = Clock()
    app = create_app(tmp_path, FakeArcFace(), clock)
    with TestClient(app) as admin, TestClient(app) as phone:
        admin.get('/admin')
        pairing = admin.get('/api/pairing').json()['terminal_url']
        token = parse_qs(urlparse(pairing).query)['pair'][0]
        phone.get('/terminal',params={'pair':token})
        dashboard = admin.get('/api/dashboard').json()
        assert dashboard['model'] == ARCFACE_MODEL_ID
        assert dashboard['needs_recapture'] == 1
        assert dashboard['people'][0]['samples'] == 0 and dashboard['people'][0]['total_samples'] == 6
        assert admin.get('/api/recognition/audit').json()['unusable'][0]['id'] == person_id
        assert not Gallery(store.gallery(ARCFACE_MODEL_ID),512).groups
        assert admin.post(f'/api/people/{person_id}/enrollment').status_code == 200
        for index in range(3):
            clock.value += .35
            assert phone.post('/api/frames',content=f'cancel-{index}'.encode()).status_code == 200
        admin.delete('/api/enrollments')
        assert [row['embedding'] for row in store.gallery(SFACE_MODEL_ID)] == old_vectors
        assert not store.gallery(ARCFACE_MODEL_ID)
        admin.post(f'/api/people/{person_id}/enrollment')
        for index in range(6):
            clock.value += .35
            result = phone.post('/api/frames',content=f'capture-{index}'.encode()).json()
        assert result['status'] == 'enrolled' and result['person_id'] == person_id
        dashboard = admin.get('/api/dashboard').json()
        assert dashboard['needs_recapture'] == 0 and len(dashboard['people']) == 1
        assert dashboard['people'][0]['samples'] == 6 and dashboard['people'][0]['total_samples'] == 12
        assert [row['embedding'] for row in store.gallery(SFACE_MODEL_ID)] == old_vectors
        assert all(len(row['embedding']) == 512 * 4 for row in store.gallery(ARCFACE_MODEL_ID))
        assert store.report(session_id)['present'] == 1 and store.report(session_id)['people'][0]['person_id'] == person_id
        started = admin.post('/api/sessions',json={'name':'После','group_names':['Группа 1']}).json()
        for index in range(3):
            clock.value += .35
            result = phone.post('/api/frames',content=f'check-{index}'.encode()).json()
        assert result['status'] == 'present'
        assert store.report(started['id'])['present'] == 1


def test_policies_do_not_reuse_old_model_thresholds(tmp_path):
    legacy = {**asdict(Policy()), 'threshold': .71}
    (tmp_path / 'recognition.json').write_text(json.dumps(legacy))
    arcface = load_policy(tmp_path, ARCFACE_MODEL_ID)
    sface = load_policy(tmp_path, SFACE_MODEL_ID)
    assert arcface.threshold == .52 and sface.threshold == .71
    assert policy_path(tmp_path, ARCFACE_MODEL_ID) != policy_path(tmp_path, SFACE_MODEL_ID)
    assert json.loads((tmp_path / 'recognition.json').read_text()) == legacy
    assert load_policy(tmp_path, ARCFACE_MODEL_ID) == arcface


def test_model_selection_and_weight_integrity(tmp_path, monkeypatch):
    data = tmp_path / 'data'
    data.mkdir()
    selected = Vision.from_settings(tmp_path / 'models', data)
    assert selected.model_id == ARCFACE_MODEL_ID and selected.embedding_size == 512
    assert not selected.ready
    models = tmp_path / 'models'
    models.mkdir()
    (models / 'yunet.onnx').write_bytes(b'wrong-detector')
    (models / 'arcface.onnx').write_bytes(b'wrong-model')
    assert not selected.ready
    with pytest.raises(FrameError):
        selected.analyze(b'anything')
    monkeypatch.setenv('FACE_RECOGNIZER','sface')
    rollback = Vision.from_settings(models,data)
    assert rollback.model_id == SFACE_MODEL_ID and rollback.embedding_size == 128
    assert json.loads((data / 'vision.json').read_text())['recognizer'] == 'arcface'
    with pytest.raises(ValueError):
        Vision(models,'unknown-model')


def test_arcface_alignment_recovers_similarity_transform():
    angle = .17
    rotation = np.array([[np.cos(angle),-np.sin(angle)],[np.sin(angle),np.cos(angle)]])
    transformed = 1.7 * (ARCFACE_POINTS @ rotation.T) + [120,45]
    matrix = similarity_transform(transformed)
    aligned = transformed @ matrix[:,:2].T + matrix[:,2]
    np.testing.assert_allclose(aligned, ARCFACE_POINTS, atol=1e-6)
    with pytest.raises(FrameError):
        similarity_transform(np.zeros((5,2)))
    with pytest.raises(FrameError):
        similarity_transform(np.ones((4,2)))
