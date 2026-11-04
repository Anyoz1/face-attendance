"""Conservative open-set identification; similarity scores are not probabilities."""
from dataclasses import asdict, dataclass, replace
import json
from pathlib import Path

import numpy as np


ENROLLMENT_SAMPLES = 6


@dataclass(frozen=True)
class Policy:
    threshold: float = .55
    margin: float = .10
    support_threshold: float = .50
    centroid_threshold: float = .50
    min_support: int = 2
    top_templates: int = 3
    confirmation_frames: int = 3
    confirmation_seconds: float = .60
    continuity_threshold: float = .50
    enrollment_consistency: float = .60
    duplicate_threshold: float = .70
    review_similarity: float = .60

    def __post_init__(self):
        for key in ('threshold', 'margin', 'support_threshold', 'centroid_threshold',
                    'continuity_threshold', 'enrollment_consistency', 'duplicate_threshold', 'review_similarity'):
            value = getattr(self, key)
            if not isinstance(value, (int, float)) or not np.isfinite(value) or not 0 <= value <= 1:
                raise ValueError(f'Некорректный параметр распознавания: {key}')
        if any(type(getattr(self, key)) is not int for key in ('min_support', 'top_templates', 'confirmation_frames')):
            raise ValueError('Число шаблонов и кадров должно быть целым')
        if not 2 <= self.min_support <= self.top_templates <= ENROLLMENT_SAMPLES:
            raise ValueError('Нужно минимум два подтверждающих шаблона')
        if (not isinstance(self.confirmation_seconds, (int, float))
                or not np.isfinite(self.confirmation_seconds)
                or not 3 <= self.confirmation_frames <= 10 or not .3 <= self.confirmation_seconds <= 5):
            raise ValueError('Некорректное подтверждение по времени')

    @classmethod
    def load(cls, path: Path):
        if path.exists():
            return cls(**json.loads(path.read_text()))
        policy = cls()
        path.write_text(json.dumps(asdict(policy), indent=2) + '\n')
        return policy


def unit(vector):
    vector = np.asarray(vector, dtype=np.float32).reshape(-1)
    norm = float(np.linalg.norm(vector))
    if not vector.size or not np.isfinite(vector).all() or not np.isfinite(norm) or norm < 1e-8:
        return None
    return vector / norm


class Gallery:
    def __init__(self, rows, dimension=128):
        self.groups = []
        vectors, people = [], {}
        for row in rows:
            try:
                vector = np.frombuffer(row['embedding'], dtype=np.float32)
            except (TypeError, ValueError):
                continue
            if vector.shape != (dimension,):
                continue
            vector = unit(vector)
            if vector is None:
                continue
            person = people.setdefault(row['id'], {'id': row['id'], 'name': row['name'], 'indices': []})
            person['indices'].append(len(vectors))
            vectors.append(vector)
        self.matrix = np.stack(vectors) if vectors else np.empty((0, dimension), np.float32)
        for person in people.values():
            centroid = unit(self.matrix[person['indices']].mean(axis=0))
            if centroid is not None:
                self.groups.append({**person, 'centroid': centroid})

    def ranked(self, embedding, policy):
        embedding = unit(embedding)
        if embedding is None or embedding.shape != self.matrix.shape[1:] or not self.groups:
            return []
        similarities = self.matrix @ embedding
        ranked = []
        for person in self.groups:
            values = similarities[person['indices']]
            top = np.sort(values)[-policy.top_templates:]
            ranked.append({'id': person['id'], 'name': person['name'],
                           'score': float(top.mean()),
                           'centroid_score': float(person['centroid'] @ embedding),
                           'support': int(np.sum(values >= policy.support_threshold)),
                           'samples': len(values)})
        return sorted(ranked, key=lambda item: item['score'], reverse=True)

    def identify(self, embedding, policy):
        ranked = self.ranked(embedding, policy)
        if not ranked:
            return None
        best = ranked[0]
        runner_up = ranked[1]['score'] if len(ranked) > 1 else -1
        if (best['support'] < policy.min_support or best['score'] < policy.threshold
                or best['centroid_score'] < policy.centroid_threshold
                or best['score'] - runner_up < policy.margin):
            return None
        return {**best, 'margin': best['score'] - runner_up}

    def duplicate(self, embeddings, policy):
        average = unit(np.mean(embeddings, axis=0))
        ranked = self.ranked(average, policy)
        if ranked:
            best = ranked[0]
            if (best['score'] >= policy.duplicate_threshold
                    and best['centroid_score'] >= policy.duplicate_threshold
                    and best['support'] >= policy.min_support):
                return best
        return None

    def audit(self, policy):
        insufficient, inconsistent, similar = [], [], []
        for person in self.groups:
            samples = self.matrix[person['indices']]
            info = {'id': person['id'], 'name': person['name']}
            if len(samples) < ENROLLMENT_SAMPLES:
                insufficient.append({**info, 'samples': len(samples)})
            pairwise = samples @ samples.T
            if pairwise.size and float(pairwise.min()) < policy.enrollment_consistency:
                inconsistent.append(info)
        for index, first in enumerate(self.groups):
            for second in self.groups[index + 1:]:
                score = float(first['centroid'] @ second['centroid'])
                if score >= policy.review_similarity:
                    similar.append({'first': {'id': first['id'], 'name': first['name']},
                                    'second': {'id': second['id'], 'name': second['name']}, 'score': score})
        similar.sort(key=lambda pair: pair['score'], reverse=True)
        return {'people': len(self.groups), 'insufficient': insufficient,
                'inconsistent': inconsistent, 'similar': similar,
                'note': 'Проверка базы не измеряет точность на новых съёмках.'}


class Confirmation:
    def __init__(self, policy):
        self.policy = policy
        self.reset()

    def reset(self):
        self.person = None
        self.anchor = None
        self.evidence = []
        self.started_at = None

    @property
    def progress(self):
        return min(.95, len(self.evidence) / self.policy.confirmation_frames)

    def observe(self, embedding, candidate, gallery, timestamp):
        if (candidate['id'] != self.person or self.anchor is None
                or float(self.anchor @ embedding) < self.policy.continuity_threshold
                or (self.evidence and timestamp - self.evidence[-1][0] > 2)):
            self.reset()
            self.person, self.anchor = candidate['id'], embedding.copy()
            self.started_at = timestamp
        self.evidence.append((timestamp, embedding.copy()))
        self.evidence = self.evidence[-self.policy.confirmation_frames:]
        if (len(self.evidence) < self.policy.confirmation_frames
                or timestamp - self.started_at + 1e-9 < self.policy.confirmation_seconds):
            return False
        aggregate = unit(np.mean([item[1] for item in self.evidence], axis=0))
        verified = gallery.identify(aggregate, self.policy)
        if verified is None or verified['id'] != self.person:
            self.reset()
            return False
        return True


def match(embedding, rows, threshold=None, margin=None, policy=None):
    policy = policy or Policy()
    if threshold is not None:
        policy = replace(policy, threshold=threshold)
    if margin is not None:
        policy = replace(policy, margin=margin)
    return Gallery(rows, len(embedding)).identify(embedding, policy)
