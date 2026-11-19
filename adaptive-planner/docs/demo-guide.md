# 查询示例

在完整仓库根目录执行：

```powershell
# 无数据依赖
.\adaptive-planner\scripts\demo-all.ps1 -Build -Synthetic
# SIFT资产齐全时，连续执行以下七个场景
.\adaptive-planner\scripts\demo-all.ps1 -Build
```

| 场景 | 条件 | 目标Recall | 观察点 |
|---|---|---:|---|
| 较宽随机过滤 | R-rank-100000 | .95 | 根据目录选择N/10 |
| 稀疏随机过滤 | R-rank-1000 | .95 | 精确E |
| 相关簇过滤 | C-rank-100000 | .95 | 与相近比例的R条件比较 |
| 未知条件 | region=1 | .95 | E回退，Actual Recall为null |
| 冷位图 | R-rank-100000 | .95 | 缺少冷Tuning记录时使用E |
| 低质量目标 | R-rank-100000 | .05 | P/10；有GT时检查是否需要E回退 |
| 扩大候选池 | R-rank-100000 | .20 | O/40，保留真实返回数量 |

输出显示输入、匹配比例、目标、规划与最终策略、预算、理由、结果ID、耗时和GT检查。单查询耗时不是P99。

## 单次与交互查询

```powershell
.\adaptive-planner\run.ps1 -Filter R-rank-100000 -Recall 0.95 -MaxBudget 40
.\adaptive-planner\run.ps1 -Interactive
```

输入 `0 | R-rank-100000 | .95 | 40 | hot`；输入 `exit` 结束。

## JSON输入

```powershell
.\adaptive-planner\run.ps1 -Synthetic -Request adaptive-planner/examples/query-synthetic.json
```

向量维度需匹配：Synthetic为8，SIFT为128。运行记录写入 `adaptive-planner/.runs/`，每次使用独立文件名。
