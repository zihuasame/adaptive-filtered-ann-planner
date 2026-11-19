"""Import three inspected frozen components with package/type substitution only.
The research files stay untouched; the product has no dependency on results/classes.
"""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parents[2]
folder=ROOT/'adaptive-planner/planner-app/src/main/java/com/filteredann/planner/engine'
folder.mkdir(parents=True,exist_ok=True)
rules={'IntegratedStrategies':'FourStrategyEngine','CanonicalVectors':'RowsVectorStore','RowExactIndex':'ProductExactIndex','IntegratedFactory':'ProductGraphFactory'}
manifest=[]
for original in ['IntegratedStrategies','RowExactIndex','IntegratedFactory']:
 source=ROOT/'scripts/w3-r-efg/java'/(original+'.java');text=source.read_text(encoding='utf-8')
 adapted=text.replace('package com.filteredann.w3efg;','package com.filteredann.planner.engine;')
 for a,b in rules.items():adapted=adapted.replace(a,b)
 path=folder/(rules[original]+'.java')
 if path.exists() and path.read_text(encoding='utf-8')!=adapted:
  raise SystemExit('Product-specific changes exist; refusing to overwrite '+str(path)+'. Read docs/v0.3-performance/optimization-report.md.')
 path.write_text(adapted,encoding='utf-8')
 restored=adapted.replace('package com.filteredann.planner.engine;','package com.filteredann.w3efg;')
 for a,b in rules.items():restored=restored.replace(b,a)
 assert restored==text
 manifest.append(dict(original=str(source.relative_to(ROOT)),originalSha256=hashlib.sha256(source.read_bytes()).hexdigest(),adapter=str(path.relative_to(ROOT)),substitutionsOnly=True))
out=ROOT/'adaptive-planner/docs/research-adapter-provenance.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Three frozen components adapted with verified type/package-only changes')
