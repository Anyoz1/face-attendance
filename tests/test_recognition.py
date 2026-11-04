from dataclasses import replace

import numpy as np
import pytest

from attendance.evaluation import evaluate
from attendance.recognition import Confirmation, Gallery, Policy, unit


def synthetic_gallery(count=70, seed=23, dimension=128):
    """Synthetic vectors exercise decision rules, not real face accuracy."""
    rng = np.random.default_rng(seed)
    centers = [unit(rng.normal(size=dimension)) for _ in range(count)]
    rows = []
    for index, center in enumerate(centers):
        for _ in range(6):
            sample = unit(center + rng.normal(scale=.012, size=dimension))
            rows.append({'id': str(index), 'name': f'Person {index}', 'embedding': sample.tobytes()})
    return rows, centers


@pytest.mark.parametrize('size', [50, 70])
@pytest.mark.parametrize('dimension', [128, 512])
def test_full_roster_known_and_unknown_vectors(size, dimension):
    rows, centers = synthetic_gallery(size, dimension=dimension)
    gallery, policy = Gallery(rows, dimension), Policy()
    assert len(gallery.groups) == size
    for index, center in enumerate(centers):
        assert gallery.identify(center, policy)['id'] == str(index)
    rng = np.random.default_rng(42)
    for _ in range(100):
        assert gallery.identify(unit(rng.normal(size=dimension)), policy) is None


def test_one_high_scoring_outlier_does_not_identify_someone():
    rows, centers = synthetic_gallery()
    unknown = unit(np.random.default_rng(991).normal(size=128))
    # Simulate one contaminated stored template among six.
    rows[0]['embedding'] = unknown.tobytes()
    assert Gallery(rows).identify(unknown, Policy()) is None
    assert Gallery(rows).identify(centers[0], Policy())['id'] == '0'


def test_similar_people_are_rejected_even_in_a_large_roster():
    rows, centers = synthetic_gallery()
    close = unit(.98 * centers[0] + .02 * centers[1])
    for row in rows:
        if row['id'] == '1':
            row['embedding'] = close.tobytes()
    gallery = Gallery(rows)
    assert gallery.identify(centers[0], Policy()) is None
    assert any({pair['first']['id'], pair['second']['id']} == {'0', '1'} for pair in gallery.audit(Policy())['similar'])


def test_insufficient_and_invalid_templates_fail_closed():
    rows, centers = synthetic_gallery(1)
    rows = rows[:1]
    rows += [{'id':'bad','name':'Bad','embedding':b'odd'},
             {'id':'nan','name':'NaN','embedding':np.full(128, np.nan, np.float32).tobytes()},
             {'id':'zero','name':'Zero','embedding':np.zeros(128, np.float32).tobytes()}]
    gallery = Gallery(rows)
    assert gallery.identify(centers[0], Policy()) is None
    assert len(gallery.groups) == 1
    assert gallery.audit(Policy())['insufficient'][0]['samples'] == 1


def test_confirmation_requires_time_and_stable_identity():
    rows, centers = synthetic_gallery()
    gallery, policy = Gallery(rows), Policy()
    verifier = Confirmation(policy)
    first = gallery.identify(centers[0], policy)
    assert not verifier.observe(centers[0], first, gallery, 1.)
    assert not verifier.observe(centers[0], first, gallery, 1.2)
    assert not verifier.observe(centers[0], first, gallery, 1.4)
    assert verifier.observe(centers[0], first, gallery, 1.6)
    second = gallery.identify(centers[1], policy)
    assert not verifier.observe(centers[1], second, gallery, 1.8)
    assert verifier.person == '1' and len(verifier.evidence) == 1
    assert not verifier.observe(centers[1], second, gallery, 5.)
    assert len(verifier.evidence) == 1


def test_averaged_series_is_checked_against_current_gallery():
    rows, centers = synthetic_gallery(2)
    gallery, policy = Gallery(rows), Policy()
    candidate = gallery.identify(centers[0], policy)
    verifier = Confirmation(policy)
    verifier.observe(centers[0], candidate, gallery, 1.)
    verifier.observe(centers[0], candidate, gallery, 1.4)
    # A profile becoming ambiguous before commit must prevent attendance.
    for row in rows:
        if row['id'] == '1':
            row['embedding'] = centers[0].tobytes()
    assert not verifier.observe(centers[0], candidate, Gallery(rows), 1.8)
    assert verifier.person is None


def test_evaluation_keeps_rejections_and_missing_denominators():
    rows, centers = synthetic_gallery(2)
    gallery = Gallery(rows)
    probes = [('0',centers[0]), ('0',centers[1]), ('1',None), (None,centers[0]), (None,None)]
    result = evaluate(gallery, probes, Policy())
    assert result['known'] == 3 and result['unknown'] == 2
    assert result['correct_known'] == 1 and result['wrong_identity'] == 1
    assert result['rejected_known'] == 1 and result['false_accept_unknown'] == 1
    assert result['unusable_images'] == 2
    assert evaluate(gallery, [], Policy())['unknown_false_accept_rate'] is None


def test_policy_roundtrip_and_validation(tmp_path):
    path = tmp_path / 'recognition.json'
    assert Policy.load(path) == Policy.load(path) == Policy()
    with pytest.raises(ValueError):
        replace(Policy(), min_support=1)
    with pytest.raises(ValueError):
        replace(Policy(), threshold=float('nan'))
    with pytest.raises(ValueError):
        replace(Policy(), confirmation_seconds=0)
    with pytest.raises(ValueError):
        replace(Policy(), top_templates=3.5)


def test_dataset_rejects_capture_leakage_and_counts_bad_probes(tmp_path):
    from types import SimpleNamespace
    from scripts.evaluate_recognition import load_dataset
    from attendance.vision import FrameError

    class FakeCapture:
        embedding_size = 128

        def analyze(self, data):
            if data.startswith(b'bad'):
                raise FrameError('Плохой кадр')
            return SimpleNamespace(embedding=unit(np.ones(128)))

    enrollment = tmp_path / 'enrollment' / 'person-001'
    probe = tmp_path / 'probes' / 'person-001'
    unknown = tmp_path / 'probes' / '_unknown'
    for folder in (enrollment, probe, unknown):
        folder.mkdir(parents=True)
    for index in range(6):
        (enrollment / f'{index}.jpg').write_bytes(f'enroll-{index}'.encode())
    (probe / 'one.jpg').write_bytes(b'enroll-0')
    with pytest.raises(ValueError, match='регистрации попал'):
        load_dataset(tmp_path, FakeCapture(), Policy())
    (probe / 'one.jpg').write_bytes(b'bad-new-probe')
    (unknown / 'one.jpg').write_bytes(b'unknown-new-probe')
    gallery, probes, errors, uncovered = load_dataset(tmp_path, FakeCapture(), Policy())
    assert len(errors) == 1 and not uncovered
    metrics = evaluate(gallery, probes, Policy())
    assert metrics['known'] == 1 and metrics['rejected_known'] == 1
    assert metrics['unknown'] == 1 and metrics['false_accept_unknown'] == 1
    (unknown / 'two.jpg').write_bytes(b'unknown-new-probe')
    with pytest.raises(ValueError, match='Повтор проверочного'):
        load_dataset(tmp_path, FakeCapture(), Policy())
