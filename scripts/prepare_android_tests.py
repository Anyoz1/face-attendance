#!/usr/bin/env python3
"""Generate ignored test-only assets; no portraits are bundled in the main APK."""
from pathlib import Path
import json
import shutil
import sys
import urllib.request
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from attendance.vision import Vision, align_arcface

dest = ROOT / 'android/app/src/androidTest/assets/fixtures'
dest.mkdir(parents=True, exist_ok=True)
vision = Vision(ROOT / 'models', 'arcface')
metadata = {}
for name, url in [('a', 'https://raw.githubusercontent.com/opencv/opencv/master/samples/data/lena.jpg'),
                  ('b', 'https://raw.githubusercontent.com/matplotlib/matplotlib/main/lib/matplotlib/mpl-data/sample_data/grace_hopper.jpg')]:
    target = dest / f'{name}.jpg'
    local = ROOT / 'data/android-test-inputs' / f'{name}.jpg'
    if local.exists():
        shutil.copyfile(local, target)
    elif not target.exists():
        urllib.request.urlretrieve(url, target)
    face = vision.analyze(target.read_bytes(), require_single=True)
    # The optimized mobile detector uses 320 pixels, whereas the old desktop
    # detector can use up to 640. Compare implementations at equal resolution;
    # separately verify that the legacy profiles still pass the actual policy.
    image = cv2.imread(str(target))
    h, w = image.shape[:2]
    scale = min(1, 320 / w, 320 / h)
    padded = np.zeros((320, 320, 3), np.uint8)
    small = cv2.resize(image, (round(w * scale), round(h * scale)))
    padded[:small.shape[0], :small.shape[1]] = small
    detector = cv2.FaceDetectorYN.create(str(ROOT / 'models/yunet.onnx'), '', (320, 320), .85, .3, 100)
    _, detections = detector.detect(padded)
    assert detections is not None and len(detections) == 1
    detection = detections[0].copy()
    detection[:14] /= scale
    aligned = align_arcface(image, detection)
    blob = cv2.dnn.blobFromImage(aligned, 1 / 127.5, (112, 112), (127.5, 127.5, 127.5), swapRB=True, crop=False)
    vision.recognizer.setInput(blob)
    mobile_reference = vision.recognizer.forward().flatten()
    mobile_reference /= np.linalg.norm(mobile_reference)
    metadata[name] = {'source': url, 'embedding': mobile_reference.tolist(), 'legacy_embedding': face.embedding.tolist(), 'box': face.box,
                      'scope': 'Equal-resolution 320px reference plus independent legacy 640px profile compatibility.'}
(dest / 'reference.json').write_text(json.dumps(metadata))
previous = ROOT / 'data/evaluation/public-faces-20261104'
if (previous / 'faces94-report.json').exists():
    report = json.loads((previous / 'faces94-report.json').read_text())
    cache = json.loads((previous / 'features.json').read_text())['images']
    gallery = []
    for person in report['enrollment_selection']:
        entry = {'id': person['id'], 'name': person['id'], 'group': 'Test',
                 'samples': [cache[x]['embedding'] for x in person['selected_enrollment']]}
        for image in person['held_out_probes']:
            if 'embedding' in cache[image]:
                name = f'probe-{len(gallery)}.jpg'
                shutil.copyfile(previous / image, dest / name)
                entry['probe'] = name
                break
        gallery.append(entry)
    (dest / 'gallery.json').write_text(json.dumps(gallery))
    unknown, seen = [], set()
    for probe in report['runs'][-1]['predictions']:
        image = probe['image']
        identity = str(Path(image).parent)
        if probe['expected'] is not None or 'quality_error' in probe or identity in seen:
            continue
        seen.add(identity)
        name = f'unknown-{len(unknown)}.jpg'
        shutil.copyfile(previous / image, dest / name)
        unknown.append(name)
        if len(unknown) == 20:
            break
    (dest / 'unknown.json').write_text(json.dumps(unknown))
    print(f'Prepared {len(gallery)} real identities with separate enrollment/probe images, plus {len(unknown)} unknown people.')
print(f'Test assets: {dest}')
