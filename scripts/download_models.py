#!/usr/bin/env python3
"""Download pinned OpenCV Zoo models and verify Git LFS SHA-256 digests."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
COMMIT = '48b10fa505aeb51fbd127fc60a5c660040e823b7'
MODELS = {
    'yunet.onnx': ('face_detection_yunet/face_detection_yunet_2023mar.onnx',
                   '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'),
    'sface.onnx': ('face_recognition_sface/face_recognition_sface_2021dec.onnx',
                  '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'),
}


def valid(path, digest):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest


def main():
    directory = ROOT / 'models'
    directory.mkdir(exist_ok=True)
    for name, (source, digest) in MODELS.items():
        target = directory / name
        if valid(target, digest):
            print(f'{name}: проверено')
            continue
        url = f'https://media.githubusercontent.com/media/opencv/opencv_zoo/{COMMIT}/models/{source}'
        print(f'Скачиваем {name}…', flush=True)
        temporary = target.with_suffix('.download')
        try:
            with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as output:
                while block := response.read(1024 * 1024):
                    output.write(block)
            if not valid(temporary, digest):
                raise RuntimeError(f'Не совпадает SHA-256 модели {name}')
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
        print(f'{name}: загружено и проверено')


if __name__ == '__main__':
    main()
