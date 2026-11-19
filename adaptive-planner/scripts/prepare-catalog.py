"""Extract immutable research evidence into a small distributable product catalog.
No Java/ANN invocation. Holdout rows are reference-only; planner selection uses tuning.
"""
from pathlib import Path
import csv,json,hashlib,statistics,sys
ROOT=Path(__file__).resolve().parents[2]
PRODUCT=ROOT/'adaptive-planner'
SOURCE=ROOT/'results/w3-r-ef-final002/postprocess-20261119T050558/engineering-20261119T002913'

def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def write(p,v):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,indent=2,ensure_ascii=False,allow_nan=False),encoding='utf-8')

matrix=SOURCE/'exports/complete-experiment-matrix.csv'
package={x['path'].replace('\\','/'):x for x in read(SOURCE.parent/'final-package-sha256.json')}
assert sha(matrix)==package[str(matrix.relative_to(ROOT)).replace('\\','/')]['sha256']
with matrix.open(encoding='utf-8',newline='') as f:rows=list(csv.DictReader(f))
cost_file=ROOT/'results/w3-r-analysis/tables/per-round-recomputed.csv'
analysis_manifest={x['path'].replace('\\','/'):x for x in read(ROOT/'results/w3-r-analysis/sha256-manifest.json')}
assert sha(cost_file)==analysis_manifest[str(cost_file.relative_to(ROOT)).replace('\\','/')]['sha256']
with cost_file.open(encoding='utf-8',newline='') as f:costs={int(x['cellId']):x for x in csv.DictReader(f)}
assert len(rows)==531 and {r['build_id'] for r in rows}=={'engineering-20261119T002913-single-graph'}
base=ROOT/'data/raw/sift_base.fvecs';metadata=ROOT/'data/processed/metadata/sift1m-random-v1.bin'
labels=SOURCE/'labels.bin'
dataset=dict(id='sift1m-prefix-250000-d128',count=250000,dimensions=128,baseSha256=sha(base),metadataSha256=sha(metadata),labelsSha256=sha(labels),querySha256=sha(ROOT/'data/raw/sift_query.fvecs'))
groups={}
for row in rows:groups.setdefault((row['filter'],row['strategy'],int(row['budget']),row['scenario']),[]).append(row)
filters=[]
for f in read(SOURCE/'measured/filters.json'):
 fid=f['id'];points=[]
 for (filter_id,s,b,scenario),g in sorted(groups.items()):
  if filter_id!=fid:continue
  assert len(g)==3 and {int(x['round']) for x in g}=={0,1,2}
  rounds=[dict(round=int(x['round']),strictRecall=float(x['strict']),tieAwareRecall=float(x['tieAware']),p50Ms=float(x['p50_ns'])/1e6,p95Ms=float(x['p95_ns'])/1e6,p99Ms=float(x['p99_ns'])/1e6,e2eMeanMs=float(x['meanNs'])/1e6,qps=float(x['qps_recomputed']),visitedCount=float(x['visited']),expandedCount=float(x['expanded']),insufficientRate=float(x['insufficient_rate']),queries=int(x['queries'])) for x in sorted(g,key=lambda x:int(x['round']))]
  for r,x in zip(rounds,sorted(g,key=lambda x:int(x['round']))):
   c=costs[int(x['cellId'])]
   assert c['run_id']==x['run_id'] and c['build_id']==x['build_id']
   r.update(bitmapMeanMs=float(c['bitmap_mean_ms']),bitmapP99Ms=float(c['bitmap_p99_ms']),workerE2EMeanMs=float(c['worker_mean_ms']),workerE2EP99Ms=float(c['worker_p99_ms']),graphSearchP99Ms=float(c['graph_p99_ns'])/1e6)
  points.append(dict(strategy=s,budget=b,scenario=scenario,selectionEligible=scenario=='tuning',rounds=rounds,strictMin=min(x['strictRecall'] for x in rounds),tieMedian=statistics.median(x['tieAwareRecall'] for x in rounds),e2eMeanMedianMs=statistics.median(x['e2eMeanMs'] for x in rounds),p99MedianMs=statistics.median(x['p99Ms'] for x in rounds)))
 filters.append(dict(id=fid,bitmapSha256=f['intersectionHash'],matches=f['intersectionCount'],selectivity=f['intersectionCount']/250000,family=f['fullFrozen']['family'],conditionCount=2 if 'category' in fid or '-random-' in fid else 1,points=points))
assets=PRODUCT/'performance-catalog'
for f in filters:
 mask=SOURCE/'audit-output'/(f['id']+'.prefix.bitmap')
 assert sha(mask)==f['bitmapSha256']
 raw=mask.read_bytes();assert len(raw)==31250 and sum(b.bit_count() for b in raw)==f['matches']
 target=assets/'bitmaps'/(f['id']+'.bitmap');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
catalog=dict(schemaVersion=1,version='v0.1',dataset=dataset,sourceRunId=rows[0]['run_id'],sourceBuildId=rows[0]['build_id'],selectionRule='THREE_ROUND_STRICT_MIN_THEN_MEDIAN_E2E_MEAN',topK=10,buildParameters=dict(maxDegree=32,beamWidth=100,neighborOverflow=1.2,alpha=1.2,hierarchy=True,refine=True,compression='NONE'),historicalBuild=read(SOURCE/'measured/build.json'),filters=filters,limitations=['Historic single-build evidence; current-build performance is unknown until measured','Recall is dataset mean, not a per-query lower bound','No cold tuning coverage; holdout must never select a plan'])
write(assets/'catalog-v0.1.json',catalog)
inputs=[matrix,SOURCE/'measured/filters.json',SOURCE/'measured/build.json',SOURCE/'labels.bin',metadata,base,ROOT/'data/raw/sift_query.fvecs']
inputs += [SOURCE/'audit-output'/(f['id']+'.prefix.bitmap') for f in filters]
inputs.append(cost_file)
write(assets/'source-manifest.json',dict(sourceKind='REAL_LOCAL_MEASUREMENTS',rows=531,tuningPoints=104,graphCount=1,artifacts=[dict(path=str(p.relative_to(ROOT)),sha256=sha(p)) for p in inputs],catalogSha256=sha(assets/'catalog-v0.1.json')))
print('CATALOG_EXTRACTED',len(filters),'filters',sum(len(x['points']) for x in filters),'points; no ANN executed')
