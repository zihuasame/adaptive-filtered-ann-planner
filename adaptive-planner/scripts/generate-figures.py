"""Offline README figures from the shipped, signed historical catalog. No ANN runs."""
from pathlib import Path
import csv
import hashlib
import json
import statistics
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap

PRODUCT = Path(__file__).resolve().parents[1]
CATALOG = PRODUCT / 'performance-catalog/catalog-v0.1.json'
OUT = PRODUCT / 'docs/figures'
OUT.mkdir(parents=True, exist_ok=True)
raw = CATALOG.read_bytes()
manifest = json.loads((CATALOG.parent / 'source-manifest.json').read_text(encoding='utf-8'))
assert hashlib.sha256(raw).hexdigest() == manifest['catalogSha256']
catalog = json.loads(raw)
filters = catalog['filters']
COLORS = dict(E='#59636e', P='#e89b25', O='#008c95', N='#7259bb')
plt.rcParams.update({'font.size': 9, 'axes.spines.top': False, 'axes.spines.right': False})

def median(point, key):
    return statistics.median(r[key] for r in point['rounds'])

def tuning(f):
    return [p for p in f['points'] if p['scenario'] == 'tuning']

def save(fig, name, note):
    fig.text(.5, .012, note, ha='center', fontsize=8)
    fig.tight_layout(rect=(0, .045, 1, .94))
    fig.savefig(OUT / name, dpi=150)
    plt.close(fig)

def panels(title):
    fig, axes = plt.subplots(2, 4, figsize=(16, 7))
    fig.suptitle(title, fontsize=15)
    return fig, axes.flat

# Fixed measured endpoints expose low-quality strategies rather than hiding them.
for metric, name, title, ylabel in [
    ('strictRecall', 'recall-comparison.png', 'Strict Recall@10: E exact, P/O/N candidateK=640', 'Strict Recall@10'),
    ('p99Ms', 'p99-comparison.png', 'P99 E2E latency: E exact, P/O/N candidateK=640', 'P99 E2E (ms, log scale)')]:
    fig, axes = panels(title)
    for ax, f in zip(axes, filters):
        points = [next(p for p in tuning(f) if p['strategy'] == s and p['budget'] == (0 if s == 'E' else 640)) for s in COLORS]
        values = [median(p, metric) for p in points]
        lower = [v - min(r[metric] for r in p['rounds']) for p, v in zip(points, values)]
        upper = [max(r[metric] for r in p['rounds']) - v for p, v in zip(points, values)]
        ax.bar(list(COLORS), values, color=list(COLORS.values()), yerr=[lower, upper], capsize=3)
        ax.set_title(f"{f['id']}\nmatching fraction={f['selectivity']:.4%}", fontsize=9)
        ax.set_ylabel(ylabel)
        if metric == 'strictRecall':
            ax.set_ylim(0, 1.08)
            ax.axhline(.95, color='#b22', linestyle='--', linewidth=.8)
        else:
            ax.set_yscale('log')
    save(fig, name, 'HISTORICAL 250k SIFT prefix / TUNING n=1,000 / 3 rounds: median + min/max. Different quality; not a quality-matched speed ranking.')

fig, axes = panels('Measured Recall-P99 trade-off (all measured Tuning budgets; no interpolation)')
for ax, f in zip(axes, filters):
    for strategy, color in COLORS.items():
        points = [p for p in tuning(f) if p['strategy'] == strategy]
        for p in points:
            x = min(r['strictRecall'] for r in p['rounds'])
            y = median(p, 'p99Ms')
            ax.scatter(x, y, color=color, s=30, marker='s' if strategy == 'E' else 'o', label=strategy if p is points[0] else None)
            ax.annotate(str(p['budget']), (x, y), xytext=(3, 3), textcoords='offset points', fontsize=6)
    ax.axvline(.95, color='#b22', linestyle='--', linewidth=.7)
    ax.set_title(f['id'], fontsize=9)
    ax.set_xlabel('Strict Recall@10 (minimum across rounds)')
    ax.set_ylabel('P99 E2E ms (median across rounds)')
    ax.set_yscale('log')
    ax.legend(fontsize=7)
save(fig, 'recall-p99-tradeoff.png', 'HISTORICAL 250k / TUNING ONLY / E budget=0; P/O/N=10,40,160,640. Labels are measured budgets; lines do not invent intermediate points.')

targets = [.05, .20, .90, .95, .99, 1.0]
selection = []
grid, labels = [], []
for f in filters:
    row, text = [], []
    for target in targets:
        eligible = [p for p in tuning(f) if min(r['strictRecall'] for r in p['rounds']) >= target and (target != 1 or p['strategy'] == 'E')]
        chosen = min(eligible, key=lambda p: (median(p, 'e2eMeanMs'), p['budget'], p['strategy']))
        row.append(list(COLORS).index(chosen['strategy']))
        text.append(f"{chosen['strategy']}/{chosen['budget']}")
        selection.append(dict(filter=f['id'], selectivity=f['selectivity'], target=target, strategy=chosen['strategy'], budget=chosen['budget'], strict_min=min(r['strictRecall'] for r in chosen['rounds']), tuning_mean_median_ms=median(chosen, 'e2eMeanMs'), tuning_p99_median_ms=median(chosen, 'p99Ms'), tuning_qps_median=median(chosen, 'qps')))
    grid.append(row)
    labels.append(text)
fig, ax = plt.subplots(figsize=(12, 6))
ax.imshow(grid, cmap=ListedColormap(list(COLORS.values())), vmin=-.5, vmax=3.5, aspect='auto')
ax.set_xticks(range(len(targets)), [f'{t:.0%}' for t in targets])
ax.set_yticks(range(len(filters)), [f"{f['id']} ({f['selectivity']:.3%})" for f in filters])
ax.set_xlabel('Requested Strict Recall target')
ax.set_title('Historical hot-bitmap choices: quality first, then median of 3 E2E means')
for i, texts in enumerate(labels):
    for j, text in enumerate(texts):
        ax.text(j, i, text, color='white' if grid[i][j] != 1 else 'black', ha='center', va='center')
save(fig, 'strategy-selection.png', 'TUNING selection only; not a new graph prediction. Unknown/cold requests -> exact E. target=100% -> exact E; no interpolated budget.')
with (OUT / 'historical-selection.csv').open('w', encoding='utf-8', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(selection[0]))
    writer.writeheader()
    writer.writerows(selection)

# Separate Holdout table: descriptive evaluation of the already measured selections.
holdout = []
for f in filters:
    for p in f['points']:
        if p['scenario'] == 'tuning':
            continue
        holdout.append(dict(filter=f['id'], strategy=p['strategy'], budget=p['budget'], scenario=p['scenario'], strict_min=min(r['strictRecall'] for r in p['rounds']), tie_median=median(p, 'tieAwareRecall'), p99_median_ms=median(p, 'p99Ms'), e2e_mean_median_ms=median(p, 'e2eMeanMs'), qps_median=median(p, 'qps'), queries_per_round=p['rounds'][0]['queries']))
with (OUT / 'holdout-reference.csv').open('w', encoding='utf-8', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(holdout[0]))
    writer.writeheader()
    writer.writerows(holdout)
(OUT / 'manifest.json').write_text(json.dumps(dict(source_catalog_sha256=manifest['catalogSha256'], source_build_id=catalog['sourceBuildId'], nodes=250000, figure_scenario='TUNING', uncertainty='min/max of three historical rounds; no new measurements', files=[dict(path=p.name, sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted(OUT.iterdir()) if p.suffix in {'.png', '.csv'}]), indent=2), encoding='utf-8')
print(f'FIGURES_GENERATED: 4 PNG, {len(selection)} Tuning choices, {len(holdout)} Holdout reference points; zero ANN queries')
