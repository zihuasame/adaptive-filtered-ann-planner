"""Read-only preflight for optional historical SIFT demo assets. No download/generation."""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
PRODUCT = ROOT / 'adaptive-planner'
manifest = json.loads((PRODUCT / 'performance-catalog/source-manifest.json').read_text(encoding='utf-8'))
names = ['data/raw/sift_base.fvecs', 'data/raw/sift_query.fvecs', 'data/processed/metadata/sift1m-random-v1.bin']
checks = [(name, ROOT / name) for name in names]
labels = next(x for x in manifest['artifacts'] if x['path'].replace('\\', '/').endswith('/labels.bin'))
label_path = PRODUCT / 'data/labels.bin'
if not label_path.exists():
    label_path = ROOT / labels['path']
checks.append((labels['path'], label_path))
expected = {x['path'].replace('\\', '/'): x['sha256'] for x in manifest['artifacts']}
failed = False
for key, path in checks:
    if not path.is_file():
        print('MISSING', path.relative_to(ROOT))
        failed = True
        continue
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    passed = h.hexdigest() == expected[key.replace('\\', '/')]
    print('PASS' if passed else 'HASH_MISMATCH', path.relative_to(ROOT), 'bytes=', path.stat().st_size, 'sha256=', h.hexdigest())
    failed |= not passed
print('GT is optional: runtime verifies each supplied GT file; absent GT -> actualRecall=null.')
sys.exit(1 if failed else 0)
