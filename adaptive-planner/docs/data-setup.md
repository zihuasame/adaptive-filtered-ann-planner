# 数据准备

Synthetic示例不需要外部数据。SIFT示例使用完整SIFT1M文件的前250k向量，并要求元数据与性能目录一致。

| 文件 | 项目相对路径 |
|---|---|
| SIFT基础向量 | `data/raw/sift_base.fvecs` |
| SIFT查询 | `data/raw/sift_query.fvecs` |
| 随机属性 | `data/processed/metadata/sift1m-random-v1.bin` |
| 簇标签 | `adaptive-planner/data/labels.bin` |
| 可选过滤GT | `adaptive-planner/data/engineering-gt/` |

原始向量可从 [INRIA/TexMex](http://corpus-texmex.irisa.fr/) 获取。基础文件516,000,000字节，查询文件5,160,000字节。元数据、簇标签和过滤GT是本项目派生资产，当前没有公共下载包；仅有官方SIFT向量时，请使用Synthetic入口。

SHA-256见[数据清单](../performance-catalog/source-manifest.json)。准备好资产后检查并运行：

```powershell
python adaptive-planner/scripts/check-data.py
.\adaptive-planner\scripts\demo-all.ps1 -Build
```

GT目录包含八个`.gtbin`及`validation-summary.json`。无GT仍可检索，Actual Recall为null。官方全库无过滤Top-100不能代替250k前缀的过滤GT。

SIFT运行使用Java21、Panama和 `-Xms1g -Xmx4g`，启动要求系统Available至少2 GiB。数据目录不纳入源码包。
