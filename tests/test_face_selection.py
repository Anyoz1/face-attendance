from pathlib import Path
import time

import numpy as np
import pytest

from attendance.app import Runtime
from attendance.recognition import Policy
from attendance.store import Store
from attendance.vision import ARCFACE_MODEL_ID, Face, FrameError, Vision, select_face


def detection(cx, cy, width, height):
    row = np.zeros(15, np.float32)
    row[:4] = [cx - width / 2, cy - height / 2, width, height]
    row[-1] = .99
    return row


@pytest.mark.parametrize('reverse', [False, True])
def test_center_face_wins_over_people_at_frame_edges(reverse):
    center = detection(320, 240, 160, 200)
    rows = np.stack([detection(65, 230, 120, 160), center, detection(580, 220, 100, 130)])
    if reverse:
        rows = rows[::-1]
    np.testing.assert_array_equal(select_face(rows, 640, 480), center)


def test_large_foreground_face_wins_over_small_nearby_background_face():
    foreground = detection(390, 240, 200, 260)
    background = detection(250, 210, 85, 100)
    np.testing.assert_array_equal(select_face(np.stack([background, foreground]), 640, 480), foreground)


def test_center_position_resolves_equal_size_faces():
    center = detection(320, 220, 120, 150)
    side = detection(150, 220, 120, 150)
    np.testing.assert_array_equal(select_face(np.stack([side, center]), 640, 480), center)


def test_nearly_equal_people_need_clearer_position_instead_of_arbitrary_detection_order():
    rows = np.stack([detection(220, 240, 140, 180), detection(420, 240, 140, 180)])
    for detections in (rows, rows[::-1]):
        with pytest.raises(FrameError, match='Два лица рядом'):
            select_face(detections, 640, 480)


def test_registration_still_requires_one_face_even_with_small_bystander():
    rows = np.stack([detection(320, 240, 180, 220), detection(40, 60, 30, 40)])
    with pytest.raises(FrameError, match='Для регистрации'):
        select_face(rows, 640, 480, require_single=True)


def test_multiple_people_at_edges_need_someone_to_approach_center():
    rows = np.stack([detection(60, 240, 100, 150), detection(580, 240, 100, 150)])
    with pytest.raises(FrameError, match='к центру'):
        select_face(rows, 640, 480)


def test_single_face_keeps_existing_off_center_capture_behavior_and_is_copied():
    row = detection(140, 170, 100, 130)
    selected = select_face(np.stack([row]), 640, 480)
    np.testing.assert_array_equal(selected, row)
    selected[0] = 999
    assert row[0] != 999


@pytest.mark.parametrize('rows', [None, np.empty((0, 15), np.float32)])
def test_no_face_is_rejected(rows):
    with pytest.raises(FrameError, match='Посмотрите в камеру'):
        select_face(rows, 640, 480)


def unit(index):
    value = np.zeros(512, np.float32)
    value[index] = 1
    return value


class SelectionVision:
    model_id = ARCFACE_MODEL_ID
    embedding_size = 512

    def __init__(self):
        self.modes = []

    def analyze(self, data, *, require_single=False):
        self.modes.append(require_single)
        if data.startswith(b'multi') and require_single:
            raise FrameError('Для регистрации в кадре должен быть только один человек')
        if data.startswith(b'dim'):
            raise FrameError('Нужно более ровное освещение лица', box=[.3, .2, .4, .6])
        return Face(unit(1 if b'B' in data else 0), [.3, .2, .4, .6], 100, 0)


def runtime(tmp_path):
    vision = SelectionVision()
    store = Store(tmp_path / 'test.sqlite3')
    state = Runtime(store, vision, Policy(), clock=lambda: clock[0])
    clock = [1000.]

    def frame(data):
        clock[0] += .35
        return state.frame(data, 'test-terminal')
    return state, frame


def test_switching_selected_person_requires_three_new_frames(tmp_path):
    state, frame = runtime(tmp_path)
    first = state.store.add_person('A', 'Test', [unit(0)] * 6, ARCFACE_MODEL_ID)
    second = state.store.add_person('B', 'Test', [unit(1)] * 6, ARCFACE_MODEL_ID)
    session = state.store.start('Multi-face', 'Test')
    assert frame(b'multi-A-1')['status'] == 'recognizing'
    assert frame(b'multi-A-2')['status'] == 'recognizing'
    assert frame(b'multi-B-1')['status'] == 'recognizing'
    assert frame(b'multi-B-2')['status'] == 'recognizing'
    assert frame(b'multi-B-3')['status'] == 'present'
    rows = {p['person_id']: p for p in state.store.report(session)['people']}
    assert not rows[first]['present_at'] and rows[second]['present_at']
    assert state.vision.modes == [False] * 5


def test_enrollment_rejects_multi_face_frames_and_uses_six_single_face_frames(tmp_path):
    state, frame = runtime(tmp_path)
    state.store.set_pending('New person', 'Test', time.time())
    result = frame(b'multi-A-0')
    assert result['status'] == 'adjust' and not state.samples
    assert not state.store.people()
    for index in range(6):
        result = frame(f'single-A-{index}'.encode())
    assert result['status'] == 'enrolled'
    assert state.vision.modes == [True] * 7


def test_quality_error_shows_selected_face_and_resets_confirmation(tmp_path):
    state, frame = runtime(tmp_path)
    state.store.add_person('A', 'Test', [unit(0)] * 6, ARCFACE_MODEL_ID)
    state.store.start('Multi-face', 'Test')
    frame(b'multi-A-1')
    frame(b'multi-A-2')
    error = frame(b'dim-A-3')
    assert error['status'] == 'adjust' and error['box'] == [.3, .2, .4, .6]
    assert frame(b'multi-A-4')['status'] == 'recognizing'


def compose_faces(sources, placements):
    """Construct a multi-face fixture from public portraits, preserving aspect ratio.

    These compositions test selection and application mechanics, not video accuracy.
    Each placement is (source_index, face_center_x, face_center_y, face_width).
    """
    import cv2
    canvas = np.full((480, 640, 3), (70, 95, 70), np.uint8)
    for index, cx, cy, face_width in placements:
        image, face = sources[index]
        h, w = image.shape[:2]
        x, y, fw, fh = np.array(face.box) * [w, h, w, h]
        x0, y0 = max(0, int(x - .4 * fw)), max(0, int(y - .3 * fh))
        x1, y1 = min(w, int(x + 1.4 * fw)), min(h, int(y + 1.3 * fh))
        scale = face_width / fw
        crop = cv2.resize(image[y0:y1, x0:x1], None, fx=scale, fy=scale)
        left, top = round(cx - (x + fw / 2 - x0) * scale), round(cy - (y + fh / 2 - y0) * scale)
        assert 0 <= left and 0 <= top and left + crop.shape[1] <= 640 and top + crop.shape[0] <= 480
        canvas[top:top + crop.shape[0], left:left + crop.shape[1]] = crop
    return canvas


@pytest.mark.parametrize('recognizer', ['arcface', 'sface'])
def test_real_faces_select_one_subject_and_reject_ambiguous_pair(pytestconfig, recognizer):
    import cv2
    files = [pytestconfig.getoption('--face-test-image'), pytestconfig.getoption('--face-test-other-image')]
    if not all(files):
        pytest.skip('real image paths not supplied')
    vision = Vision(Path(__file__).resolve().parent.parent / 'models', recognizer)
    if not vision.ready:
        pytest.skip(f'{recognizer} model weights not installed')
    sources = [(cv2.imread(file), vision.analyze(Path(file).read_bytes(), require_single=True)) for file in files]
    cases = [([(1, 75, 180, 70), (0, 320, 240, 180)], 0),
             ([(0, 75, 180, 70), (1, 320, 240, 180)], 1),
             ([(1, 210, 230, 90), (0, 400, 240, 180)], 0)]
    for placements, expected in cases:
        _, encoded = cv2.imencode('.jpg', compose_faces(sources, placements))
        data = encoded.tobytes()
        # Prove both faces were detected, including the small bystander.
        with pytest.raises(FrameError, match='Для регистрации'):
            vision.analyze(data, require_single=True)
        selected = vision.analyze(data)
        assert float(selected.embedding @ sources[expected][1].embedding) > .7
        assert float(selected.embedding @ sources[1 - expected][1].embedding) < .5
    _, encoded = cv2.imencode('.jpg', compose_faces(sources, [(0, 215, 240, 135), (1, 425, 240, 135)]))
    with pytest.raises(FrameError, match='Два лица рядом'):
        vision.analyze(encoded.tobytes())
