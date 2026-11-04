from dataclasses import dataclass
from pathlib import Path
import threading

import cv2
import numpy as np

from attendance.recognition import match  # Compatibility for existing callers.


MODEL_ID = "sface-2021dec-v1"  # Never mix vectors from different models.


class FrameError(ValueError):
    pass


@dataclass
class Face:
    embedding: np.ndarray
    box: list[float]
    sharpness: float
    yaw: float


class Vision:
    def __init__(self, models: Path):
        self.models = models
        self.lock = threading.Lock()
        self.detector = None
        self.recognizer = None
        cv2.setNumThreads(2)

    @property
    def ready(self):
        return all((self.models / f).is_file() for f in ('yunet.onnx', 'sface.onnx'))

    def analyze(self, data: bytes) -> Face:
        if not self.ready:
            raise FrameError("Модели не установлены. Запустите scripts/download_models.py")
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
                self.recognizer = cv2.FaceRecognizerSF.create(str(self.models / 'sface.onnx'), '')
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
            aligned = self.recognizer.alignCrop(img, face)
            gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
            sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
            if sharpness < 25:
                raise FrameError("Изображение размыто — держитесь неподвижно")
            if float(gray.mean()) < 35 or float(gray.mean()) > 225:
                raise FrameError("Нужно более ровное освещение лица")
            feature = self.recognizer.feature(aligned).flatten().astype(np.float32)
        norm = np.linalg.norm(feature)
        if not np.isfinite(feature).all() or norm < 1e-8:
            raise FrameError("Не удалось обработать лицо")
        return Face(feature / norm, [float(x / w), float(y / h), float(fw / w), float(fh / h)], sharpness, yaw)
