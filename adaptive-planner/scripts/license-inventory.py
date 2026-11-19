"""Inventory the exact resolved Maven artifacts; never change project licensing."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET
import zipfile

PRODUCT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
OUT = PRODUCT / 'docs/dependency-licenses.json'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def pom_licenses(pom, repository, seen=None):
    seen = set() if seen is None else seen
    if not pom.is_file() or pom in seen:
        return [], []
    seen.add(pom)
    doc = ET.parse(pom)
    result = [dict(name=x.findtext('m:name', namespaces=NS), url=x.findtext('m:url', namespaces=NS)) for x in doc.findall('m:licenses/m:license', NS)]
    evidence = [dict(pom=str(pom.relative_to(repository)).replace('\\', '/'), sha256=sha(pom))]
    if result:
        return result, evidence
    parent = doc.find('m:parent', NS)
    if parent is not None:
        g, a, v = [parent.findtext('m:' + key, namespaces=NS) for key in ['groupId', 'artifactId', 'version']]
        if g and a and v and '${' not in g+a+v:
            inherited, parents = pom_licenses(repository / g.replace('.', '/') / a / v / f'{a}-{v}.pom', repository, seen)
            return inherited, evidence + parents
    return [], evidence

entries = []
for raw in (PRODUCT / 'planner-app/target/classpath.txt').read_text().strip().split(';'):
    path = Path(raw)
    if '.m2' not in path.parts:
        continue
    repository = next(p for p in path.parents if p.name == 'repository')
    licenses, poms = pom_licenses(path.with_suffix('.pom'), repository)
    with zipfile.ZipFile(path) as jar:
        names = [n for n in jar.namelist() if not n.endswith('/') and ('LICENSE' in n.upper() or 'NOTICE' in n.upper())]
        embedded = [dict(entry=n, sha256=hashlib.sha256(jar.read(n)).hexdigest()) for n in names]
        # SLF4J intentionally declares its MIT license in the artifact rather than its POM.
        if path.name.startswith('slf4j-api-'):
            text = jar.read('META-INF/LICENSE.txt').decode('utf-8')
            assert 'Permission is hereby granted' in text
            licenses = [dict(name='MIT', url='https://www.slf4j.org/license.html')]
        if path.name.startswith('commons-math3-'):
            assert b'Apache License' in jar.read('META-INF/LICENSE.txt')
            licenses = [dict(name='Apache-2.0', url='https://www.apache.org/licenses/LICENSE-2.0')]
    entries.append(dict(artifact=str(path.relative_to(repository)).replace('\\', '/'), jar_sha256=sha(path), licenses=licenses, pom_evidence=poms, embedded_notices=embedded))
assert entries and all(x['licenses'] for x in entries), 'Unresolved license: inspect original POM/JAR'
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(dict(project_license='Apache-2.0', resolved_artifacts=entries), indent=2), encoding='utf-8')
print(f'LICENSE_INVENTORY: {len(entries)} artifacts; Apache-2.0 project license')
