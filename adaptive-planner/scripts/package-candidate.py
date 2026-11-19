"""Build a source ZIP with privacy checks and an artifact manifest."""
from pathlib import Path
import datetime
import hashlib
import json
import re
import subprocess
import sys
import uuid
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parent))
from privacy_scan import assert_clean

ROOT = Path(__file__).resolve().parents[2]
PRODUCT = ROOT / 'adaptive-planner'
OUT = PRODUCT / '.runs/public-candidates' / (datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:6])
OUT.mkdir(parents=True, exist_ok=False)

def git_files(*paths, untracked=False):
    command = ['git', 'ls-files', '-z', '--cached']
    if untracked:
        command += ['--others', '--exclude-standard']
    command += ['--', *paths]
    return [p.decode('utf-8') for p in subprocess.check_output(command, cwd=ROOT).split(b'\0') if p]

files = set(git_files('pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'bench-core/pom.xml', 'bench-core/src', 'bench-jvector/pom.xml', 'bench-jvector/src'))
files.update(git_files('adaptive-planner', untracked=True))
files.add('.github/workflows/adaptive-planner.yml')
files.update(['scripts/w3-r-efg/java/IntegratedStrategies.java', 'scripts/w3-r-efg/java/IntegratedFactory.java', 'scripts/w3-r-efg/java/RowExactIndex.java'])
for name in files:
    path = ROOT / name
    assert path.is_file(), name
    assert path.stat().st_size < 5 * 1024 * 1024, f'Unexpected large file: {name}'
    assert not set(Path(name).parts) & {'target', '.runs', '.tools', 'data', 'results', '.git', '.venv', '__pycache__'}, name
    assert path.suffix.lower() not in {'.bin', '.fvecs', '.ivecs', '.bvecs', '.gtbin', '.class', '.jar', '.hprof'}, name
entries = []
assert_clean((name,(ROOT/name).read_bytes()) for name in sorted(files))
readme=(PRODUCT/'README.md').read_text(encoding='utf-8')
readme=re.sub(r'\]\(([^)]+)\)',lambda m:']('+('adaptive-planner/'+m.group(1) if '://' not in m.group(1) and not m.group(1).startswith('#') else m.group(1))+')',readme)
zip_path = OUT / 'adaptive-filtered-ann-planner-0.3.0-candidate.zip'
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in sorted(files):
        raw = (ROOT / name).read_bytes()
        archive.writestr(name, raw)
        entries.append(dict(path=name, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest()))
    archive.writestr('README.md',readme)
    archive.writestr('LICENSE',(PRODUCT/'LICENSE').read_bytes())
    archive.writestr('NOTICE',(PRODUCT/'NOTICE').read_bytes())
    archive.writestr('.gitignore', '**/target/\n.tools/\n**/.runs/\ndata/\nresults/\n**/__pycache__/\n.env\n.env.*\n*.pem\n*.key\n*.p12\n*.pfx\n.idea/\n.vscode/\n*.iml\n')
    archive.writestr('CANDIDATE-MANIFEST.json', json.dumps(entries, indent=2))
with zipfile.ZipFile(zip_path) as archive:
    assert_clean((name,archive.read(name)) for name in archive.namelist())
(OUT / 'candidate-package.json').write_text(json.dumps(dict(archive=zip_path.name, bytes=zip_path.stat().st_size, sha256=hashlib.sha256(zip_path.read_bytes()).hexdigest(), source_files=len(entries), excludes=['datasets', 'GT', 'labels', 'JARs', 'targets', 'local tools', 'research results']), indent=2), encoding='utf-8')
print('SOURCE_PACKAGE', zip_path.name, len(entries), 'files', zip_path.stat().st_size, 'bytes')
