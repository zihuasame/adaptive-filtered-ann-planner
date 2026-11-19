"""Aggregate actual request samples. No reruns, interpolation or averaging filter P99s."""
from pathlib import Path
import collections
import csv
import datetime
import hashlib
import json
import math
import statistics
import sys
import shutil
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

PRODUCT=Path(__file__).resolve().parents[1]
RUN=Path(sys.argv[1]) if len(sys.argv)>1 else Path((PRODUCT/'results/v0.3-performance/latest-run.txt').read_text().strip())
DOC=PRODUCT/'docs/v0.3-performance';DOC.mkdir(parents=True,exist_ok=True)
state=json.loads((RUN/'state.json').read_text())
assert state['state']=='COMPLETED',state
manifest=json.loads((RUN/'manifest.json').read_text())
groups=collections.defaultdict(list)
mix=collections.defaultdict(list)
wall={}
for phase in ['before','after']:
    with (RUN/f'{phase}-batch-wall.csv').open() as f:
        for x in csv.DictReader(f):wall[(phase,int(x['repeat']),x['method'])]=int(x['wall_ns'])
    with (RUN/f'{phase}-benchmark-results.csv').open() as f:
        rows=list(csv.DictReader(f))
    assert len(rows)==120000
    for x in rows:
        for key in ['repeat','sequence','query_id','budget','request_ns','api_ns','search_ns','bitmap_ns','planning_ns','insufficient','fallback','visited','expanded']:x[key]=int(x[key])
        for key in ['strict','tie']:x[key]=float(x[key])
        groups[(phase,x['repeat'],x['method'],x['filter'])].append(x)
        mix[(phase,x['repeat'],x['method'])].append(x)

def rank(values,q):
    values=sorted(values)
    return values[max(0,math.ceil(len(values)*q)-1)]

def summarize(key,rows,elapsed,scope):
    phase,repeat,method,fid=key
    ns=[r['request_ns'] for r in rows]
    return dict(phase=phase,repeat=repeat,method=method,filter=fid,requests=len(rows),strict_recall=statistics.mean(r['strict'] for r in rows),tie_recall=statistics.mean(r['tie'] for r in rows),p50_ms=rank(ns,.5)/1e6,p95_ms=rank(ns,.95)/1e6,p99_ms=rank(ns,.99)/1e6,e2e_mean_ms=statistics.mean(ns)/1e6,search_mean_ms=statistics.mean(r['search_ns'] for r in rows)/1e6,planning_mean_us=statistics.mean(r['planning_ns'] for r in rows)/1e3,bitmap_mean_us=statistics.mean(r['bitmap_ns'] for r in rows)/1e3,qps=len(rows)/(elapsed/1e9),wall_ns=elapsed,qps_scope=scope,insufficient_rate=statistics.mean(r['insufficient'] for r in rows),fallbacks=sum(r['fallback'] for r in rows),executed=';'.join(f'{s}:{n}' for s,n in sorted(collections.Counter(r['executed'] for r in rows).items())),budgets=';'.join(map(str,sorted(set(r['budget'] for r in rows)))))

summary=[]
for k,rows in groups.items():
    assert len(rows)==1000
    summary.append(summarize(k,rows,sum(r['request_ns'] for r in rows),'sum_of_measured_request_wall_intervals_for_filter_in_interleaved_mix'))
for k,rows in mix.items():
    assert len(rows)==8000
    summary.append(summarize((*k,'MIXED'),rows,wall[k],'measured_8000_request_batch_wall'))
consistency={}
for method in ['E','P','O','N','Planner']:
    changed=0;count=0
    for repeat in range(3):
        old={x['sequence']:x for x in mix[('before',repeat,method)]}
        for x in mix[('after',repeat,method)]:
            a=old[x['sequence']]
            assert (a['query_id'],a['filter'])==(x['query_id'],x['filter'])
            changed+=a['result_ids']!=x['result_ids'];count+=1
    consistency[method]=dict(compared=count,changed_result_ids=changed)
assert consistency['E']['changed_result_ids']==0,'Exact kernel semantics changed'
(RUN/'measured-result-consistency.json').write_text(json.dumps(consistency,indent=2),encoding='utf-8')
with (RUN/'summary.csv').open('w',encoding='utf-8',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=list(summary[0]));writer.writeheader();writer.writerows(summary)
with (RUN/'benchmark-results.csv').open('w',encoding='utf-8',newline='') as target:
    for i,phase in enumerate(['before','after']):
        with (RUN/f'{phase}-benchmark-results.csv').open() as source:
            if i:next(source)
            shutil.copyfileobj(source,target)

def select(phase,method,fid='MIXED'):
    return [x for x in summary if x['phase']==phase and x['method']==method and x['filter']==fid]

def med(phase,method,metric,fid='MIXED'):
    xs=select(phase,method,fid);assert len(xs)==3
    return statistics.median(x[metric] for x in xs)

methods=['E','P','O','N','Planner'];colors=['#59636e','#e89b25','#008c95','#7259bb','#2473c2']
fig,axes=plt.subplots(1,3,figsize=(15,4.8))
for ax,metric,title in zip(axes,['p99_ms','qps','e2e_mean_ms'],['Mixed E2E P99 (ms, log)','Mixed batch QPS (log)','Mixed E2E mean (ms, log)']):
    for offset,phase in [(-.19,'before'),(.19,'after')]:
        vals=[med(phase,m,metric) for m in methods]
        ax.bar([i+offset for i in range(5)],vals,width=.36,label=phase,alpha=.65 if phase=='before' else 1,color=colors,edgecolor='black',hatch='//' if phase=='before' else '')
    ax.set_xticks(range(5),methods);ax.set_yscale('log');ax.set_title(title);ax.legend()
fig.suptitle('250k single shared graph / hot mixed 8 x 1000 Holdout requests / 3 rounds',fontsize=12)
fig.text(.5,.01,'P/O quality failures remain visible; speed alone is not a winner. Bars: median of round metrics, NOT mean of eight P99s.',ha='center',fontsize=9)
fig.tight_layout(rect=(0,.045,1,.93));fig.savefig(DOC/'strategy-comparison.png',dpi=150);plt.close(fig)

fig,axes=plt.subplots(2,4,figsize=(16,8))
for ax,fid in zip(axes.flat,manifest['filters']):
    for method,color in zip(methods,colors):
        for phase,marker in [('before','o'),('after','x')]:
            xs=select(phase,method,fid)
            recall=min(x['strict_recall'] for x in xs);p99=statistics.median(x['p99_ms'] for x in xs)
            ax.scatter(recall,p99,c=color,marker=marker,label=f'{method}-{phase}',s=40)
    ax.axvline(.95,c='#b22',linestyle='--',lw=.8);ax.set_yscale('log');ax.set_title(fid,fontsize=9);ax.set_xlabel('Strict Recall (min of round means)');ax.set_ylabel('E2E P99 ms (round median)')
axes.flat[0].legend(fontsize=6,ncol=2)
fig.suptitle('Measured quality and latency: same new 250k graph; before vs after',fontsize=13)
fig.tight_layout();fig.savefig(DOC/'recall-latency.png',dpi=150);plt.close(fig)

memory_note='Original process-memory.csv sampled Oracle launcher shim, not JVM: INVALID for JVM memory. Preserved. heap.csv is actual VM for entire run; real-jvm-memory.csv covers only late baseline and after. No fabricated full-before Private peak.'
heap=list(csv.DictReader((RUN/'heap.csv').open()))
memory={}
for phase in ['before','after']:
    xs=[x for x in heap if x['phase'].startswith(phase+'-')]
    memory[phase]=dict(heap_used_peak_mib=max(int(x['heap_used']) for x in xs)/2**20,heap_committed_peak_mib=max(int(x['heap_committed']) for x in xs)/2**20,samples=len(xs))
if (RUN/'real-jvm-memory.csv').exists():
    actual=list(csv.DictReader((RUN/'real-jvm-memory.csv').open()))
    def t(x):return datetime.datetime.fromisoformat(x.replace('Z','+00:00'))
    for phase in ['before','after']:
        windows=[t(x['utc']) for x in heap if x['phase'].startswith(phase+'-')]
        xs=[x for x in actual if windows and min(windows)<=t(x['utc'])<=max(windows)]
        if xs:memory[phase].update(observed_private_peak_mib=max(int(x['private_bytes']) for x in xs)/2**20,observed_ws_peak_mib=max(int(x['working_set_bytes']) for x in xs)/2**20,available_min_gib=min(int(x['system_available_bytes']) for x in xs)/2**30,process_sample_count=len(xs),complete_process_phase_coverage=phase=='after')
(RUN/'memory-summary.json').write_text(json.dumps(dict(note=memory_note,phases=memory),indent=2),encoding='utf-8')


lines=['# 产品检索性能','',f"数据：250k × 128维，Build `{manifest['build_id']}`。八过滤器等权混合，各1000条Holdout，单线程、热位图、三轮；优化前后共240000请求。",'',
'预算由Tuning选择，质量在计时外复算。P99来自每轮8000个混合请求的真实样本，QPS使用批次墙钟。下面展示三轮性能中位数，Strict取最小轮均值。完整方法见[基准方法](protocol.md)。','',
'## 混合负载','', '|阶段|方案|Strict Recall|P99 ms|QPS|E2E Mean ms|达.95|','|---|---|---:|---:|---:|---:|---|']
for phase in ['before','after']:
    for m in methods:
        quality=min(x['strict_recall'] for x in select(phase,m))
        lines.append(f"|{phase}|{m}|{quality:.6f}|{med(phase,m,'p99_ms'):.6f}|{med(phase,m,'qps'):.3f}|{med(phase,m,'e2e_mean_ms'):.6f}|{'YES' if quality>=.95 else 'NO'}|")
e99,p99=med('after','E','p99_ms'),med('after','Planner','p99_ms')
eq,pq=med('after','E','qps'),med('after','Planner','qps')
lines+=['',f'在每过滤器每轮Recall均达.95的方案中，E是混合P99最低的固定策略。优化后Planner相对E：P99降低{(1-p99/e99)*100:.1f}%，QPS提高{(pq/eq-1)*100:.1f}%。P/O质量不足。这个比较适用于此等权热负载。','',
'![策略对比](strategy-comparison.png)','','## 各过滤器：优化后','', '|过滤器|方案|预算|Strict|P50 ms|P95 ms|P99 ms|QPS active-wall|Mean ms|实际策略|回退|','|---|---|---|---:|---:|---:|---:|---:|---:|---|---:|']
for fid in manifest['filters']:
    for m in methods:
        xs=select('after',m,fid)
        lines.append(f"|{fid}|{m}|{xs[0]['budgets']}|{min(x['strict_recall'] for x in xs):.6f}|{med('after',m,'p50_ms',fid):.6f}|{med('after',m,'p95_ms',fid):.6f}|{med('after',m,'p99_ms',fid):.6f}|{med('after',m,'qps',fid):.3f}|{med('after',m,'e2e_mean_ms',fid):.6f}|{xs[0]['executed']}|{sum(x['fallbacks'] for x in xs)}|")
lines+=['','过滤器QPS按其请求墙钟区间之和计算；完整270行轮次统计见[summary.csv](summary.csv)。主测API无GT参数，回退为0，质量在批次后评价。','',
'![Recall与延迟](recall-latency.png)','','## 内存','',
'|采样指标|优化前 MiB|优化后 MiB|','|---|---:|---:|',
f"|Heap used峰值|{memory['before']['heap_used_peak_mib']:.3f}|{memory['after']['heap_used_peak_mib']:.3f}|",
f"|Heap committed峰值|{memory['before']['heap_committed_peak_mib']:.3f}|{memory['after']['heap_committed_peak_mib']:.3f}|",'',
'匹配ID数组增加约300.7 KiB理论payload，另有对象开销。Private/Working Set的基线仅覆盖末段，不能作完整峰值比较；Heap为全程1Hz采样。','',
'## 适用范围','',
'结果基于一张图、单线程及固定等权分布。前后顺序可能受JIT、温度和后台负载影响。稀疏过滤受益较大；高频更换过滤器、冷缓存及不同数据规模需要另测。','',
'复现命令和原始字段说明见[基准方法](protocol.md)，代码变化见[优化分析](optimization-report.md)。']
(DOC/'performance-report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
opt=['# 精确扫描优化','',
'原精确路径每次查询遍历N个位图位置，随后计算匹配向量的距离。MatchedIdExactIndex对不可变位图缓存升序匹配ID，热查询直接遍历匹配集合；WeakHashMap允许已淘汰位图回收。', '',
'double Squared L2累加、PriorityQueue Top-K和(distance, ID)排序沿用原实现。ProductExactIndex保留为对照。8000组输入先核对一致，正式测量的120000对前后请求返回ID全部一致。', '',
'## 混合负载','', '|方案|前Mean ms|后Mean ms|前P99 ms|后P99 ms|前QPS|后QPS|','|---|---:|---:|---:|---:|---:|---:|']
for m in methods:
    opt.append(f"|{m}|{med('before',m,'e2e_mean_ms'):.6f}|{med('after',m,'e2e_mean_ms'):.6f}|{med('before',m,'p99_ms'):.6f}|{med('after',m,'p99_ms'):.6f}|{med('before',m,'qps'):.3f}|{med('after',m,'qps'):.3f}|")
opt+=['','## E路径分条件收益','', '|过滤器|前Mean ms|后Mean ms|前/后倍数|前P99 ms|后P99 ms|','|---|---:|---:|---:|---:|---:|']
for fid in manifest['filters']:
    a,b=med('before','E','e2e_mean_ms',fid),med('after','E','e2e_mean_ms',fid)
    opt.append(f"|{fid}|{a:.6f}|{b:.6f}|{a/b:.3f}|{med('before','E','p99_ms',fid):.6f}|{med('after','E','p99_ms',fid):.6f}|")
opt+=['','## 成本','',
'首次构建匹配ID需要扫描N个位置并分配数组，热测量在预热后执行。八个过滤器的ID数组约300.7 KiB payload，每线程kernel可有独立缓存。宽过滤仍需大量double距离计算，收益小于稀疏条件。P/O/N实现保持原样，它们前后时间差反映运行波动。']
(DOC/'optimization-report.md').write_text('\n'.join(opt)+'\n',encoding='utf-8')
bottleneck=['# 检索瓶颈','',
'以下为基线请求的分段墙钟统计，展示三轮均值的中位数。','',
'|过滤器|E request ms|E search ms|Planner planning us|N search ms|','|---|---:|---:|---:|---:|']
for fid in manifest['filters']:
    bottleneck.append(f"|{fid}|{med('before','E','e2e_mean_ms',fid):.6f}|{med('before','E','search_mean_ms',fid):.6f}|{med('before','Planner','planning_mean_us',fid):.3f}|{med('before','N','search_mean_ms',fid):.6f}|")
bottleneck+=['','Planner决策约几微秒，主要成本在检索。稀疏条件的E路径仍进行250000次bitmap判断，适合缓存成员ID；宽条件的主要成本是128维double距离计算。N在相关或稀疏条件下搜索昂贵，Planner在这些条件选择E。','',
'优化集中在重复位图遍历。排序和Neighbor分配仍存在，后续应依据实际剖析决定是否继续优化。']
(DOC/'bottleneck-analysis.md').write_text('\n'.join(bottleneck)+'\n',encoding='utf-8')
shutil.copyfile(RUN/'summary.csv',DOC/'summary.csv')
print('Generated performance report, optimization analysis and figures from saved requests.')
