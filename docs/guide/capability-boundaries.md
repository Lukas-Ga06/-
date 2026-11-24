# 能力边界与默认口径

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.12 / Node 20） | 最后验证：2026-11-23 | 事实来源：`travel-agent-server/src/main/resources/application.yml`、`src/main/java/com/travel/agent/{agent,rag,service}/`、`travel-agent-web/src/`、`docs/review/2026-11-22-real-model-acceptance.md`

本文回答一个容易被 README 的「核心能力」列表盖住的问题：**不配置任何 Key 时，产品实际是什么？** 结论先说：

> 默认体验是「**规则规划器 + 内置目录 + 本地估算**」，不是「大模型自主规划真实世界行程」。
> 模型、语义 Embedding、实时路线/POI、对象存储与 Redis 都是**显式开启的增强项**。
> 天气是唯一一个默认就走真实数据、且失败时**明确说排不了**的数据源。

对外介绍、验收报告和发布判断都以本文口径为准；历史文档里的「自动降级、功能完整」不再作为能力承诺。

---

## 1. 默认路径（零 Key，开箱即跑）

| 能力 | 默认实现 | 数据性质 | 用户能看到的标注 |
| --- | --- | --- | --- |
| 意图识别 | `IntentAgent` 规则优先；`MessageParser` 负责天数 / 预算 / 日期 / 人数 | 确定性规则 | — |
| 行程生成 | 规则规划器：内置城市目录、模板、预算测算 | **目录数据 + 参考价** | `priceProvider=reference-estimate`、「参考价」标签 |
| 审核与重规划 | `ReflectAgent` + `PlanRepairService`（雨天换室内、超预算删条目、转场后移，≤2 次） | 本地确定性计算 | Diff 里的风险/警告清单 |
| RAG | `LocalHashEmbeddingService`（256 维字符 bigram 哈希）+ 内存余弦 | **词面匹配，不是语义向量** | 引用带 `documentId/chunkId/score`；分数只在本向量空间内有意义 |
| 路线 / 转场 | haversine 直线估算 | `estimated` | `source=estimated`；真实路况不可得时不画实线 |
| 价格 | 本地参考价，无房态/库存 | `reference-estimate` | 价格旁固定免责声明 |
| 天气 | **Open-Meteo 真实预报**（免费、无需 Key） | `real` | 不可用即如实说明；依赖天气的修改直接拒绝（见 §3） |
| 地图 | 未配 `VITE_AMAP_KEY` 时走 OSM / OpenFreeMap 底图 | 可降级 | 地图不可用时明确提示「时间轴仍可用」 |

判断一个能力是不是「真的」，看 `/api/system/status` 的 `*Provider` / `*Verified` 字段，不要看功能是否存在。

## 2. 可选增强（显式配置后才生效）

| 增强 | 开启方式 | 开启后得到什么 | 失败/关闭时的实际行为 |
| --- | --- | --- | --- |
| 大模型 | `LLM_API_KEY`（+ `LLM_MODEL`，默认 `deepseek-flash`） | 意图识别、结构化规划、方案润色走模型 | 规则规划器 + 模板兜底；回复来自模板，不声称「模型已审核」 |
| 语义 Embedding | `EMBEDDING_ENABLED=true` + `EMBEDDING_API_KEY` | 真实语义检索 | 进程内**永久锁存**回本地哈希；切换模型必须清库重建（见 [架构](architecture.md) §7） |
| 高德 POI / 路线 | `AMAP_KEY`（Web 服务 Key） | 真实 POI、驾车路线与折线 | QPS 限流/失败时回落估算并标注 `source=estimated`；状态接口 `routeProvider` 如实报 `estimated` |
| 高德 MCP | `MCP_ENABLED=true` + `AMAP_MAPS_API_KEY` | 模型可调用 `mcp_amap_*` 工具 | 单个 server 失败只告警，不影响启动 |
| 前端高德底图 | `VITE_AMAP_KEY` + `VITE_AMAP_SECURITY_CODE` | JS API 地图与真实折线 | 回退 OSM 底图 |
| Redis 限流 | `RATE_LIMIT_STORE=redis` | 多实例共享配额 | fail-open 放行并记指标 |
| S3 照片 | `PHOTOS_STORE=s3` + `S3_*` | 对象存储 | 写入失败返回 500，不静默丢文件 |

## 3. 天气：默认真实，失败明确说「排不了」

2026-11-23 起，产品路径**没有任何模拟天气**（`travel.weather.simulation` 默认 `false`，仅测试/离线演示可开）。
这条约束由 `WeatherService` 与 `WeatherLookup` 强制：查询要么返回可用预报，要么返回「不可用 + 原因」，不再返回一个猜出来的 `WeatherInfo`。

| 场景 | 系统行为 |
| --- | --- |
| 用户要求依赖天气的修改（消息含「雨 / 天气 / 下雨 / 雪 / 台风」）且真实天气不可用 | **明确拒绝（`status=FAILED`，内部业务码 503）+ 原因文案，不生成修改方案**（不写待确认方案、不出 Diff） |
| 普通规划期间天气不可用 | 方案照常生成，但审核只写「未获取真实天气（原因），本次未做雨天/户外校验」，**不按天气做硬约束**，回复不提「因为下雨」 |
| 真实预报是晴天，用户仍说「明天下雨换掉户外」 | 按用户意图替换（规则路径），回复只说「建议调整」，**不伪造降雨结论** |
| 出发日期超出 Open-Meteo 16 天窗口 | 视为不可用（原因 `outside-forecast-window`），不猜天气 |
| 测试 / 离线演示 | 只有显式 `WEATHER_SIMULATION=true` 才返回确定性模拟数据，`/api/system/status` 自报 `weatherProvider=simulated` |

不可用时的用户文案（`PlannerAgent` 抛出，原文）：

> 没拿到 上海 11月24日 的真实天气（网络不可达）。为避免用模拟天气改错行程，本次没有生成修改方案；可以稍后重试，或直接告诉我换成哪些室内景点。

可观测与自报：

- `/api/system/status`：`weatherProvider`（`open-meteo|simulated|unavailable`）、`weatherLastError`、`weatherHint`。
- 指标：`external.degraded{provider="weather",reason=...}`，reason 复用统一词表（`timeout/rate_limited/server_error/bad_response/connection_error`）。
- 失败负缓存默认 60 秒（`WEATHER_FAILURE_CACHE_SECONDS`）：离线时一次三日游审核不会每次都等连接超时。

## 4. RAG 的真实性边界

- 默认向量是**字符哈希**（一元 + 二元组投影、L2 归一化），相似度反映的是字面重合，不是语义。
- 问答阈值 `RAG_MIN_SCORE=0.05` 只用于滤掉几乎无关的片段；**分数不能跨 Embedding 模型比较，也不能当作置信度**。
- 评估集约 7 条，基线就是本地哈希（`RagEvaluationTest`）；真实 Embedding 的质量验收**没有做**，因此不要把「RAG 引用」当作语义正确的证据。
- 切换 `travel.embedding.model` 或开关真实 Embedding 后必须清空 `rag_document` / `rag_chunk` / `rag_document_version` 重建，详见 [架构](architecture.md) §7.4。

## 5. 验收与发布门槛现状

| 项 | 现状 | 是否发布门槛 |
| --- | --- | --- |
| 后端回归 | `mvn -B verify`：单元/集成测试 + JaCoCo + Checkstyle + SpotBugs，离线、不调真实模型 | ✅ CI 硬门禁 |
| 前端回归 | typecheck + lint + format + Vitest 覆盖率 + 构建预算 + OpenAPI 类型漂移 | ✅ CI 硬门禁 |
| 端到端 | `npm run e2e:isolated`：独立 H2，LLM / Embedding / AMAP / MCP 全关，天气用模拟开关 | ✅ CI 硬门禁（不联网） |
| 真实模型 | 2026-11-22 手工快照：2 次真实调用（余额 ¥0.80），已验证 `llmVerified=true`；模型名默认值已修正为 `deepseek-flash` | ❌ 手工验收，未进门禁 |
| 真实 Embedding | 未做质量验收，评估集基线仍是哈希向量 | ❌ 手工验收，未进门禁 |
| 真实高德 / MCP | 有 Key 时做过限流与降级核对；跨城同名 POI、polyline 实线、长期配额未验 | ❌ 手工验收，未进门禁 |
| 真实 S3 兼容存储 | 2026-11-24 用本地 S3 协议桩验通上传/读回/删除；真实 MinIO/AWS/OSS 凭证、HTTPS、版本控制未验（[复验 §4](../review/2026-11-24-gray-release-blockers.md#4-s3)） | ❌ 手工验收，未进门禁 |
| 真机 iOS / MySQL 生产迁移 / Linux 视觉基线 | 见 [2026-11-22 P0 执行快照](../review/2026-11-22-p0-execution.md) §4 | ❌ 发布前仍待关闭 |

**因此：CI 全绿只能证明「离线可回归」，不能证明「真实世界可用」。** 上线分级（内部演示 / 受控灰度 / 公网 v1）以 P0 执行快照 §5 为准。

## 6. 对外表述守则

| 可以这样说 | 不要这样说 |
| --- | --- |
| 「可内部演示的旅行规划助手：默认规则规划器，配置模型后获得 AI 增强」 | 「AI 自动规划真实世界行程」 |
| 「RAG 引用可追溯到文档与片段；默认向量是词面哈希」 | 「语义检索 / 语义理解」 |
| 「天气走真实预报，拿不到就明确拒排」 | 「天气自动降级、功能完整」 |
| 「路线未配高德时为本地估算，界面标注 estimated」 | 「高德实时路线」 |
| 「酒店价格为参考估算，无实时库存」 | 「实时房价」 |

相关文档：[外部依赖降级矩阵](external-dependencies.md) · [部署与运维](deployment.md) · [架构与核心设计](architecture.md) · [产品开发文档](../PRODUCT_DEVELOPMENT.md)