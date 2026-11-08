#!/usr/bin/env python3
"""Prepare pinned offline assets. Run with: uv run --with onnx python scripts/prepare_android_models.py"""
from pathlib import Path
import hashlib
import json
import shutil

import onnx

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / 'android/app/src/main/assets/models'
DEST.mkdir(parents=True, exist_ok=True)
expected = {'yunet.onnx': '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4',
            'arcface.onnx': '4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43'}
for filename, digest in expected.items():
    with (ROOT / 'models' / filename).open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == digest, filename
model = onnx.load(ROOT / 'models/yunet.onnx')
model.graph.input[0].type.tensor_type.shape.dim[2].dim_value = 320
model.graph.input[0].type.tensor_type.shape.dim[3].dim_value = 320
for output in model.graph.output:
    stride = int(output.name.rsplit('_', 1)[1])
    output.type.tensor_type.shape.dim[1].dim_value = (320 // stride) ** 2
onnx.checker.check_model(model)
onnx.save(model, DEST / 'yunet320.onnx')
shutil.copyfile(ROOT / 'models/arcface.onnx', DEST / 'arcface.onnx')
metadata = {'recognizer': 'arcface-w600k-r50-4c06341c33c2-v1', 'embedding_size': 512,
            'source_sha256': expected, 'detector_input': 320,
            'detector_change': 'Only static input/output shape metadata is changed from 640 to 320. Original detector weights are unchanged.',
            'assets_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in DEST.glob('*.onnx')}}
(DEST / 'info.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(metadata, indent=2))
