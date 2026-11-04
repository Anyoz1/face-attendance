#!/usr/bin/env python3
"""Install verified YuNet and ArcFace/SFace weights, including offline copies."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
COMMIT = '48b10fa505aeb51fbd127fc60a5c660040e823b7'
MODELS = {
    'yunet.onnx': ('face_detection_yunet/face_detection_yunet_2023mar.onnx',
                   '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'),
    'sface.onnx': ('face_recognition_sface/face_recognition_sface_2021dec.onnx',
                  '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'),
}


def valid(path, digest):
    if not path.is_file():
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == digest


def install_opencv(directory, name):
    source, digest = MODELS[name]
    target = directory / name
    if valid(target, digest):
        print(f'{name}: проверено')
        return
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


def install_arcface(directory, source=None):
    manifest = json.loads((ROOT / 'models' / 'arcface.json').read_text())
    target = directory / 'arcface.onnx'
    digest = manifest['sha256']
    if valid(target, digest):
        print('arcface.onnx: проверено')
        return
    temporary = target.with_suffix('.download')
    try:
        if source:
            if not valid(source, digest):
                raise RuntimeError('Исходный файл ArcFace не совпадает с проверенной w600k_r50')
            shutil.copyfile(source, temporary)
        else:
            urls = [manifest['upstream_release'],
                    'https://github.com/deepinsight/insightface/releases/download/model-zoo/buffalo_l.zip']
            print('Скачиваем официальный набор InsightFace buffalo_l…', flush=True)
            with tempfile.TemporaryDirectory(prefix='face-attendance-arcface-') as scratch:
                archive_path = Path(scratch) / 'buffalo_l.zip'
                last_error = None
                for url in urls:
                    try:
                        request = urllib.request.Request(url, headers={'User-Agent': 'face-attendance-model-loader'})
                        with urllib.request.urlopen(request, timeout=120) as response, archive_path.open('wb') as output:
                            size = 0
                            while block := response.read(1024 * 1024):
                                size += len(block)
                                if size > 1024 ** 3:
                                    raise RuntimeError('Архив модели слишком большой')
                                output.write(block)
                        last_error = None
                        break
                    except (OSError, RuntimeError) as exc:
                        last_error = exc
                if last_error:
                    raise RuntimeError('Не удалось скачать ArcFace. Можно указать --arcface-source /path/to/arcface.onnx') from last_error
                with zipfile.ZipFile(archive_path) as archive:
                    members = [item for item in archive.infolist() if Path(item.filename).name == manifest['canonical_filename'] and not item.is_dir()]
                    if len(members) != 1 or members[0].file_size != manifest['size_bytes']:
                        raise RuntimeError('В архиве нет ожидаемой модели w600k_r50')
                    with archive.open(members[0]) as incoming, temporary.open('wb') as output:
                        remaining = manifest['size_bytes']
                        while remaining:
                            block = incoming.read(min(1024 * 1024, remaining))
                            if not block:
                                raise RuntimeError('Файл ArcFace оборван')
                            output.write(block)
                            remaining -= len(block)
                        if incoming.read(1):
                            raise RuntimeError('Размер ArcFace не совпадает с ожидаемым')
        if not valid(temporary, digest):
            raise RuntimeError('Не совпадает SHA-256 ArcFace; файл не установлен')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    print('arcface.onnx: установлено и проверено')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recognizer', choices=('arcface','sface','all'), default='arcface')
    parser.add_argument('--arcface-source', type=Path)
    args = parser.parse_args()
    directory = ROOT / 'models'
    directory.mkdir(exist_ok=True)
    install_opencv(directory, 'yunet.onnx')
    if args.recognizer in ('arcface','all'):
        install_arcface(directory, args.arcface_source)
    if args.recognizer in ('sface','all'):
        install_opencv(directory, 'sface.onnx')


if __name__ == '__main__':
    main()
