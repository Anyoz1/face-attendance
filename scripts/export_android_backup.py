#!/usr/bin/env python3
"""Read the desktop database without changing it; export Android backup v1."""
import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sqlite3
import struct

MODEL = 'arcface-w600k-r50-4c06341c33c2-v1'


def millis(value):
    if value is None:
        return None
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return round(dt.timestamp() * 1000)


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', type=Path, default=root / 'data/attendance.sqlite3')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() == args.database.resolve():
        parser.error('Choose a separate output file')
    data = {'format': 'face-attendance-android', 'version': 1, 'model': MODEL}
    with sqlite3.connect(args.database.resolve().as_uri() + '?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        db.execute('BEGIN')
        data['groups'] = [dict(r) for r in db.execute('SELECT name FROM groups')]
        data['people'] = [dict(r) for r in db.execute('SELECT id,name,group_name,archived FROM people')]
        data['templates'] = []
        for row in db.execute('SELECT person_id,model,embedding FROM templates WHERE model=?', (MODEL,)):
            blob = row['embedding']
            if len(blob) != 2048:
                raise ValueError('Invalid ArcFace template')
            values = struct.unpack('<512f', blob)
            if not .9 < sum(v * v for v in values) < 1.1:
                raise ValueError('Invalid normalized template')
            data['templates'].append({'person_id': row['person_id'], 'model': MODEL,
                                      'embedding': base64.b64encode(blob).decode()})
        data['sessions'] = [{'id': r['id'], 'name': r['name'], 'opened_at': millis(r['opened_at']),
                             'closed_at': millis(r['closed_at'])} for r in db.execute('SELECT * FROM sessions')]
        data['session_groups'] = [dict(r) for r in db.execute('SELECT session_id,group_name FROM session_groups')]
        data['audit'] = [{'at': millis(r['at']), 'session_id': r['session_id'], 'person_id': r['person_id'],
                          'action': r['action']} for r in db.execute('SELECT * FROM audit ORDER BY at')]
        last = {(r['session_id'], r['person_id']): r['action'] for r in data['audit']}
        data['attendance'] = [{'session_id': r['session_id'], 'person_id': r['person_id'], 'name': r['name'],
                               'group_name': r['group_name'], 'present_at': millis(r['present_at']),
                               'method': r['method'], 'auto_blocked': int(r['present_at'] is None and
                                   last.get((r['session_id'], r['person_id'])) == 'manual:clear')}
                              for r in db.execute('SELECT * FROM attendance')]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump(data, stream, ensure_ascii=False)
    print(json.dumps({'active_people': sum(not p['archived'] for p in data['people']),
                      'templates': len(data['templates']), 'sessions': len(data['sessions']),
                      'output': str(args.output)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
