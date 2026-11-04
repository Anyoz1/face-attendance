#!/usr/bin/env python3
"""Evaluate an independent local set. Never changes the live database or policy."""
import argparse
from dataclasses import asdict, replace
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from attendance.evaluation import evaluate
from attendance.recognition import ENROLLMENT_SAMPLES, Gallery, Policy
from attendance.vision import FrameError, MODEL_ID, Vision

EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp'}


def images(directory):
    return sorted(path for path in directory.rglob('*') if path.is_file() and path.suffix.lower() in EXTENSIONS)


def load_dataset(root, vision, policy):
    rows, hashes, identities = [], set(), set()
    enrollment_root = root / 'enrollment'
    if not enrollment_root.is_dir() or not (root / 'probes').is_dir():
        raise ValueError('Нужны папки enrollment/<person_id>/ и probes/<person_id>/')
    for directory in sorted(enrollment_root.iterdir()):
        if not directory.is_dir():
            continue
        person_id = directory.name
        if person_id == '_unknown':
            raise ValueError('_unknown используется только в probes')
        samples, local_hashes = [], set()
        for path in images(directory):
            data = path.read_bytes()
            digest = hashlib.sha256(data).digest()
            if digest in local_hashes or digest in hashes:
                raise ValueError(f'Повтор одного кадра в регистрации: {path}')
            local_hashes.add(digest)
            face = vision.analyze(data)
            if samples and min(float(sample @ face.embedding) for sample in samples) < policy.enrollment_consistency:
                raise ValueError(f'Несогласованные лица при регистрации: {path}')
            samples.append(face.embedding)
        if len(samples) != ENROLLMENT_SAMPLES:
            raise ValueError(f'{person_id}: нужно ровно {ENROLLMENT_SAMPLES} разных кадров регистрации')
        hashes.update(local_hashes)
        identities.add(person_id)
        rows.extend({'id': person_id, 'name': person_id, 'embedding': sample.tobytes()} for sample in samples)
    if not identities:
        raise ValueError('Нет людей для регистрации')
    probes, errors, covered, probe_hashes = [], [], set(), set()
    for directory in sorted((root / 'probes').iterdir()):
        if not directory.is_dir():
            continue
        expected = None if directory.name == '_unknown' else directory.name
        if expected is not None and expected not in identities:
            raise ValueError(f'В probes неизвестная папка {directory.name}; посторонних поместите в _unknown')
        for path in images(directory):
            data = path.read_bytes()
            digest = hashlib.sha256(data).digest()
            if digest in hashes:
                raise ValueError(f'Кадр из регистрации попал в проверку: {path}. Нужна отдельная съёмка.')
            if digest in probe_hashes:
                raise ValueError(f'Повтор проверочного кадра: {path}')
            probe_hashes.add(digest)
            try:
                embedding = vision.analyze(data).embedding
            except FrameError as exc:
                # Unusable known images remain in the rejection denominator.
                errors.append({'image': str(path.relative_to(root)), 'reason': str(exc)})
                embedding = None
            probes.append((expected, embedding))
            if expected is not None:
                covered.add(expected)
    if not probes:
        raise ValueError('Нет проверочных изображений в probes')
    return Gallery(rows), probes, errors, identities - covered


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--policy', type=Path, default=ROOT / 'data' / 'recognition.json')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--sweep', action='store_true', help='Compare candidate thresholds; do not apply them')
    args = parser.parse_args()
    if args.report.resolve() in {args.policy.resolve(), (ROOT / 'data' / 'attendance.sqlite3').resolve()}:
        parser.error('Отчёт должен сохраняться отдельно от настроек и базы')
    policy = Policy(**json.loads(args.policy.read_text())) if args.policy.exists() else Policy()
    vision = Vision(ROOT / 'models')
    if not vision.ready:
        parser.error('Сначала установите модели')
    try:
        gallery, probes, errors, uncovered = load_dataset(args.dataset, vision, policy)
    except (ValueError, FrameError) as exc:
        parser.error(str(exc))
    report = {'model': MODEL_ID, 'gallery_people': len(gallery.groups), 'policy': asdict(policy),
              'metrics': evaluate(gallery, probes, policy), 'unusable_images': errors,
              'people_without_probes': sorted(uncovered),
              'scope': 'Frame-level identification on this capture set; no liveness evaluation.',
              'note': 'Ноль ошибок на этом наборе не гарантирует отсутствие ошибок в работе. Пороги после подбора проверяют на ещё одной независимой съёмке.'}
    if args.sweep:
        report['threshold_comparison'] = [
            {'threshold': threshold, 'margin': margin, 'metrics': evaluate(gallery, probes, replace(policy, threshold=threshold, margin=margin))}
            for threshold in (.50, .55, .60, .65, .70) for margin in (.08, .10, .12, .15)
        ]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'gallery_people': report['gallery_people'], **report['metrics']}, ensure_ascii=False, indent=2))
    print(f'Отчёт: {args.report}')


if __name__ == '__main__':
    main()
