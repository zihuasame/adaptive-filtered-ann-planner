# 历史性能目录

目录基于SIFT1M前250k条、128维、单图、八过滤器的三轮测量，包含104个Tuning配置和73个Holdout配置，共531轮。

- Tuning：1000查询，用于选择策略和预算。
- Holdout-hot：9000查询，用于评价热请求。
- Holdout-cold：1000查询，用于评价位图首次构建成本。

每轮保存Strict/Tie-aware Recall、P50/P95/P99、E2E均值、QPS及节点访问/展开计数。产品只用Tuning做选择。

## 图表

| 图 | 读取方式 |
|---|---|
| [Recall](figures/recall-comparison.png) | E与P/O/N candidateK=640，三轮中位数及范围 |
| [P99](figures/p99-comparison.png) | 相同端点的E2E P99；需结合Recall判断 |
| [策略选择](figures/strategy-selection.png) | 各质量目标下，三轮达标后选最低成本配置 |
| [Recall-P99](figures/recall-p99-tradeoff.png) | 所有已测预算的质量与延迟点 |

P99先按各轮请求计算，再展示轮次中位数。P/O质量不足的点保留在图中。该目录来自历史图；[V0.3产品报告](v0.3-performance/performance-report.md)是另一张图上的完整请求链路比较。

[选择表](figures/historical-selection.csv) · [Holdout表](figures/holdout-reference.csv) · [来源SHA](../performance-catalog/source-manifest.json)

生成图表需要Python与Matplotlib：

```powershell
python adaptive-planner/scripts/generate-figures.py
```
