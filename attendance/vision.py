from dataclasses import dataclass
from pathlib import Path
import hashlib
import json
import os
import threading

import cv2
import numpy as np

from attendance.recognition import match  # Compatibility for existing callers.


SFACE_MODEL_ID = 'sface-2021dec-v1'
ARCFACE_MODEL_ID = 'arcface-w600k-r50-4c06341c33c2-v1'
MODEL_ID = ARCFACE_MODEL_ID
YUNET_SHA256 = '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'
MODEL_SPECS = {
    'arcface': {'id': ARCFACE_MODEL_ID, 'name': 'ArcFace ResNet50', 'filename': 'arcface.onnx', 'dimension': 512,
                'sha256': '4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43'},
    'sface': {'id': SFACE_MODEL_ID, 'name': 'SFace', 'filename': 'sface.onnx', 'dimension': 128,
              'sha256': '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'},
}
ARCFACE_POINTS = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                           [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float64)


def similarity_transform(source, target=ARCFACE_POINTS):
    """Least-squares similarity transform (Umeyama); no extra alignment model."""
    source, target = np.asarray(source, np.float64), np.asarray(target, np.float64)
    if source.shape != (5, 2) or target.shape != (5, 2) or not np.isfinite(source).all():
        raise FrameError('Не удалось определить точки лица')
    source_mean, target_mean = source.mean(axis=0), target.mean(axis=0)
    centered_source, centered_target = source - source_mean, target - target_mean
    variance = float(np.sum(centered_source ** 2) / len(source))
    if variance < 1e-8:
        raise FrameError('Не удалось выровнять лицо')
    covariance = centered_target.T @ centered_source / len(source)
    u, singular, vt = np.linalg.svd(covariance)
    sign = np.ones(2)
    if np.linalg.det(u @ vt) < 0:
        sign[-1] = -1
    rotation = u @ np.diag(sign) @ vt
    scale = float(singular @ sign / variance)
    affine = scale * rotation
    return np.column_stack((affine, target_mean - affine @ source_mean))


def align_arcface(image, face):
    points = face[4:14].reshape(5, 2).astype(np.float64)
    points[:2] = points[:2][np.argsort(points[:2, 0])]
    points[3:] = points[3:][np.argsort(points[3:, 0])]
    transform = similarity_transform(points)
    return cv2.warpAffine(image, transform, (112, 112), flags=cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=0)


class FrameError(ValueError):
    pass


@dataclass
class Face:
    embedding: np.ndarray
    box: list[float]
    sharpness: float
    yaw: float


class Vision:
    def __init__(self, models: Path, recognizer='arcface'):
        if recognizer not in MODEL_SPECS:
            raise ValueError('Доступные модели распознавания: arcface, sface')
        self.models = models
        self.backend = recognizer
        self.spec = MODEL_SPECS[recognizer]
        self.model_id = self.spec['id']
        self.embedding_size = self.spec['dimension']
        self.display_name = self.spec['name']
        self.lock = threading.Lock()
        self.detector = None
        self.recognizer = None
        self._file_signature = None
        self._files_valid = False
        cv2.setNumThreads(2)

    @classmethod
    def from_settings(cls, models: Path, data_dir: Path):
        settings_path = data_dir / 'vision.json'
        if not settings_path.exists():
            settings_path.write_text(json.dumps({'recognizer': 'arcface'}, indent=2) + '\n')
        settings = json.loads(settings_path.read_text())
        return cls(models, os.environ.get('FACE_RECOGNIZER') or settings['recognizer'])

    @property
    def ready(self):
        files = [(self.models / 'yunet.onnx', YUNET_SHA256),
                 (self.models / self.spec['filename'], self.spec['sha256'])]
        try:
            signature = tuple((file.stat().st_size, file.stat().st_mtime_ns) for file, _ in files)
        except OSError:
            return False
        if signature != self._file_signature:
            valid = True
            for file, digest in files:
                with file.open('rb') as stream:
                    valid = valid and hashlib.file_digest(stream, 'sha256').hexdigest() == digest
            self._file_signature, self._files_valid = signature, valid
        return self._files_valid

    def analyze(self, data: bytes) -> Face:
        if not self.ready:
            raise FrameError(f'Нет проверенных весов {self.display_name}. Запустите scripts/download_models.py')
        if len(data) > 1_500_000:
            raise FrameError("Слишком большой кадр")
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise FrameError("Не удалось прочитать изображение")
        h, w = img.shape[:2]
        if min(h, w) < 160 or max(h, w) > 4096:
            raise FrameError("Неподходящий размер изображения")
        # Detection preserves aspect ratio; alignment uses the original image.
        scale = min(1.0, 640 / max(h, w))
        small = cv2.resize(img, (round(w * scale), round(h * scale)))
        with self.lock:
            if self.detector is None:
                self.detector = cv2.FaceDetectorYN.create(str(self.models / 'yunet.onnx'), '', (320, 320), .85, .3, 100)
                if self.backend == 'arcface':
                    self.recognizer = cv2.dnn.readNetFromONNX(str(self.models / self.spec['filename']))
                    self.recognizer.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
                    self.recognizer.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
                else:
                    self.recognizer = cv2.FaceRecognizerSF.create(str(self.models / self.spec['filename']), '')
            self.detector.setInputSize((small.shape[1], small.shape[0]))
            _, faces = self.detector.detect(small)
            if faces is None or len(faces) == 0:
                raise FrameError("Посмотрите в камеру")
            if len(faces) != 1:
                raise FrameError("Подходите по одному — в кадре несколько лиц")
            face = faces[0].copy()
            face[:14] /= scale
            x, y, fw, fh = face[:4]
            if min(fw, fh) < 85 or fw * fh / (w * h) < .025:
                raise FrameError("Подойдите ближе")
            if x < 0 or y < 0 or x + fw > w or y + fh > h:
                raise FrameError("Лицо должно полностью помещаться в кадре")
            eyes = face[4:8].reshape(2, 2)
            eye_distance = float(np.linalg.norm(eyes[0] - eyes[1]))
            yaw = float((face[8] - eyes[:, 0].mean()) / max(eye_distance, 1))
            if abs(yaw) > .32:
                raise FrameError("Посмотрите прямо в камеру")
            aligned = align_arcface(img, face) if self.backend == 'arcface' else self.recognizer.alignCrop(img, face)
            gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
            sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
            if sharpness < 25:
                raise FrameError("Изображение размыто — держитесь неподвижно")
            if float(gray.mean()) < 35 or float(gray.mean()) > 225:
                raise FrameError("Нужно более ровное освещение лица")
            if self.backend == 'arcface':
                blob = cv2.dnn.blobFromImage(aligned, 1 / 127.5, (112, 112),
                                             (127.5, 127.5, 127.5), swapRB=True, crop=False)
                self.recognizer.setInput(blob)
                feature = self.recognizer.forward().flatten().astype(np.float32)
            else:
                feature = self.recognizer.feature(aligned).flatten().astype(np.float32)
        norm = np.linalg.norm(feature)
        if feature.shape != (self.embedding_size,) or not np.isfinite(feature).all() or not np.isfinite(norm) or norm < 1e-8:
            raise FrameError("Не удалось обработать лицо")
        return Face(feature / norm, [float(x / w), float(y / h), float(fw / w), float(fh / h)], sharpness, yaw)
