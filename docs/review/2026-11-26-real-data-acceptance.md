# 真实数据验收：POI 质量 8/8，Embedding 阻塞待 Key（2026-11-26）

> 快照日期：2026-11-26 ｜ 范围：[花费预算评审](2026-11-25-spend-budget.md) §5 第 3 项
> 「真实 Embedding 与 POI 质量验收」
> ｜ 结论：**POI / 地理编码质量 8/8 PASS（真实高德 Web 服务 Key，REST）**；
> **真实 Embedding 仍未验收**——本机没有 `EMBEDDING_API_KEY`，且 `LLM_BASE_URL` 指向的 DeepSeek
> 不提供 `/embeddings`；验收测试 `EmbeddingLiveTest` 已就绪，一填 Key 即可跑。

## 0. 结论速览

| 项 | 结论 | 证据 |
| --- | --- | --- |
| POI 质量（5 个跨城地标） | **5/5 PASS**：top1 命中期望地标、同城、坐标落在中国境内 | [精编证据](../../.e2e/acceptance/evidence/poi-quality-20261126-summary.md)（原始输出已 gitignore） |
| 地理编码质量（3 个地址） | **3/3 PASS**：`formatted_address` 与城市正确、坐标合规 | 同上 |
| 高德限流（10021） | 首次运行 POI-4 撞 `CUQPS_HAS_EXCEEDED_THE_LIMIT`；脚本按生产策略退避重试一次后 **8/8 PASS** | 首次证据 `.e2e/poi-quality-20261126-125005/` |
| 真实 Embedding 质量 | **未验收（阻塞）**：缺 `EMBEDDING_API_KEY`；本地哈希不算真实语义 | — |
| 验收工具 | `scripts/amap-poi-quality.ps1`（新增，一条命令）；`EmbeddingLiveTest`（新增，`EMBEDDING_LIVE=true` 才跑） | 源码 |

## 1. POI / 地理编码质量验收

### 1.1 为什么需要单独一类验收

`amap-real-key-probe.ps1`（2026-11-19/23）证明的是「链路通」：起服务、规划、路线段 `source=amap`、
GCJ-02 坐标系统一。它回答不了「搜出来的点是不是那个点」——而 POI 名称/城市错配会让规划把酒店、
景点放到另一个城市，且路线仍然「成功」。

本脚本用固定评测集直连高德 REST，不启动后端、不写业务库，一次 8 个请求：

| 编号 | 查询 | 城市 | 期望 |
| --- | --- | --- | --- |
| POI-1…5 | 西湖 / 故宫 / 外滩 / 宽窄巷子 / 兵马俑 | 杭州/北京/上海/成都/西安 | top5 内出现期望关键词、POI 城市=查询城市、坐标在中国包围盒内 |
| GEO-1…3 | 西湖风景区 / 故宫博物院 / 外滩 | 杭州/北京/上海 | 地理编码返回、城市一致、坐标合规 |

判定只认「命中/同城/坐标合规」，不评价排序（排序随高德侧模型变化，不适合当硬门禁）。
Key 只在 query 里，脚本日志只打前 8 位指纹；证据文件不含 Key 与完整 URL。

### 1.2 结果（2026-11-26 12:50，真实 Key 指纹 `5f74d350****`）

| 编号 | top1 结果 | 城市 | 判定 |
| --- | --- | --- | --- |
| POI-1 | 杭州西湖风景名胜区 | 杭州市 | PASS |
| POI-2 | 故宫博物院 | 北京市 | PASS |
| POI-3 | 外滩 | 上海市 | PASS |
| POI-4 | 宽窄巷子景区 | 成都市 | PASS（首次运行撞 10021，退避重试后通过） |
| POI-5 | 秦始皇兵马俑博物馆 | 西安市 | PASS |
| GEO-1 | 浙江省杭州市西湖区 | 杭州市 | PASS |
| GEO-2 | 北京市东城区故宫博物院 | 北京市 | PASS |
| GEO-3 | 上海市浦东新区外滩 | 上海市 | PASS |

**8/8 PASS，退出码 0**。原始证据（含逐条响应、已脱敏）在本机 `.e2e/poi-quality-20261126-125013/summary.md`（目录已 gitignore）；
可入库的精编摘要：[`.e2e/acceptance/evidence/poi-quality-20261126-summary.md`](../../.e2e/acceptance/evidence/poi-quality-20261126-summary.md)。

### 1.3 顺带发现

- **10021 在「连续小请求」下也会触发**：8 个串行请求里第 4 个被拒。与 2026-11-19 的 20 并发实测一致，
  说明该 Key 的并发/QPS 配额很紧；脚本重试一次即可，但生产路径（`AmapDataService.direction`）
  已有的退避逻辑不能被删——这次是真实场景复现，不是测试环境噪声。
- 重试策略与生产一致（1 次、1.2s 退避）；把「限流」与「数据质量」分开判定，避免用重试掩盖质量回归。

## 2. 真实 Embedding：为什么没验、验什么

### 2.1 阻塞事实

- `travel-agent-server/.env.local` 只有 `LLM_API_KEY`（`https://api.deepseek.com`，无 `/embeddings` 端点）
  与高德 Key，**没有** `EMBEDDING_API_KEY`；
- `application.yml` 的 `travel.embedding.enabled` 默认 `false`；未配置 Key 时 RAG 走本地字符 bigram 哈希，
  它没有语义能力，**不能**作为「真实 Embedding 质量」的证据；
- 验收需要外部输入：任意 OpenAI 兼容 `/embeddings` 端点 + Key（SiliconFlow / 智谱 / OpenAI 等）。
  按 `capability-boundaries` 的口径，这一项在拿到 Key 之前保持 ❌。

### 2.2 已就绪的验收（一条命令）

`EmbeddingLiveTest`（`embedding/LIVE` 条件）覆盖：

1. **真实服务被激活**：`@Primary OpenAiCompatibleEmbeddingService` 生效、非空向量、归一化；
2. **语义质量**：同义地标（杭州西湖 ↔ 西湖风景名胜区、北京故宫 ↔ 紫禁城）的余弦相似度，
   必须比无关主题（汽车维修 / 手机屏幕维修）高出至少 0.05——只断言方向与余量，不断言具体数值。

运行方式（拿到 Key 后）：

```powershell
$env:EMBEDDING_LIVE = "true"
$env:EMBEDDING_ENABLED = "true"
$env:EMBEDDING_BASE_URL = "https://api.siliconflow.cn/v1"
$env:EMBEDDING_API_KEY = "<key>"
$env:EMBEDDING_MODEL = "BAAI/bge-m3"
cd travel-agent-server; mvn -B -Dtest=EmbeddingLiveTest test
```

成本：6 次短文本 embedding；不重建知识库、不写库。若换模型，先清空 `rag_document` / `rag_chunk` /
`rag_document_version` 再重建（否则新旧向量不在同一空间，检索会静默失效）。

## 3. 未验证 / 边界

- POI 评测集只有 5 个地标 + 3 个地址，覆盖「容易同名/跨城」的典型场景，不是全量质量评估；
- 未验证生产 Key（容器内 `AMAP_KEY`）与 IP 白名单；本机 Key 与生产环境可能不是同一枚；
- 未验证 MCP 分支的 POI 输出（本次走 REST）；MCP 12 工具已有 2026-11-25 的真实复跑记录；
- 真实 Embedding 的**维度/模型一致性**必须与已入库向量同源；验收只比较同一次运行内的向量，
  不证明已有知识库索引可用（那需要重建后重跑 RAG 评估集）；
- 如果未来把 `EmbeddingLiveTest` 接进 CI：需要 Secret 与成本预算，且不能用生产 Key。

## 4. 下一步

1. 提供 `EMBEDDING_API_KEY`（或确认复用某个已授权端点）后跑 `EmbeddingLiveTest`，把结论回填
   `capability-boundaries` 的「真实 Embedding」行；
2. 重建知识库后跑 RAG 评估集，比较哈希基线 vs 真实 Embedding 的引用质量；
3. 把 POI 质量脚本加入发布前手工清单（一条命令，成本 8 次请求）。