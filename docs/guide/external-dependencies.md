# 外部依赖降级矩阵（高德 / LLM / Embedding / Redis / S3）

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.7 / AWS SDK 2.25.70 / WireMock 3.13.1） | 最后验证：2026-11-19 | 事实来源：`travel-agent-server/src/main/java/com/travel/agent/{service,llm,rag,security,config}/*.java`、`src/main/resources/application.yml`、`src/test/java/com/travel/agent/resilience/*`

本文只写**代码与测试能证明的现状**：每个外部依赖的超时预算从哪来、重试到底有没有、
失败后变成什么、用户看到什么、哪个指标会涨、哪个测试类守住它。
没有重试的地方一律写 0，不把「将来要做的重试」写进现状。

## 1. 降级原则与指标契约

五个外部依赖都遵循同一条纪律：**失败即降级，不把异常抛给业务主链路**。
唯一的例外是 S3 的写入/读取（照片是用户资产，必须给用户明确的 500/404 语义，而不是静默丢文件）。

统一降级指标（由本矩阵负责埋点，可观测性文档只消费、不重复定义）：

```
external.degraded{provider=amap|llm|embedding|redis|s3,
                  reason=timeout|rate_limited|server_error|bad_response|connection_error}
```

| reason | 判定依据（优先级从高到低） |
| --- | --- |
| `timeout` | 抛出 `SocketTimeoutException` / `TimeoutException` / 类名含 `Timeout`（如 S3 `ApiCallTimeoutException`）/ 消息含 `timed out`、`timeout` |
| `rate_limited` | HTTP 429 |
| `server_error` | HTTP 5xx |
| `connection_error` | `ConnectException` / `UnknownHostException` / `SocketException` / 消息含 `connection refused`、`connection reset`、`unable to execute http request` |
| `bad_response` | 其余：空响应体、坏 JSON、业务包体 `status=0`、字段缺失、HTTP 4xx（非 429） |

- 实现：`com.travel.agent.util.ExternalDegradationMetrics`（`record(registry, provider, reason)` / `record(registry, provider, throwable)`）。
- **指标记录失败绝不反噬业务**：`record` 内部吞掉一切 `RuntimeException`；`registry == null`（纯单测/未装配）时静默跳过。
- Prometheus 侧名为 `external_degraded_total`（Micrometer 计数器转换），PromQL 示例：

```promql
# 各依赖降级速率（按根因拆分）
sum by (provider, reason) (rate(external_degraded_total[5m]))
# 任一依赖持续降级（告警起点，阈值按环境调）
sum by (provider) (rate(external_degraded_total[5m])) > 0.2
```

## 2. 降级矩阵总表

| 依赖 | 超时预算（connect / read） | 重试 | 失败后的降级结果 | 用户可见提示 | 对应测试类 |
| --- | --- | --- | --- | --- | --- |
| 高德地理编码 | 2000ms / 5000ms（可配） | **0**（批量失败退化为逐条查询，见 §3.1） | 单点返回 `null`、批量返回空/部分 Map | 无错误文案；坐标/路径精度下降（上层走 Nominatim 或本地估算） | `AmapResilienceTest` |
| LLM（OpenAI 兼容） | 5000ms / 30000ms（可配，另受 Agent 端到端预算封顶） | **0** | `null`，上层用规则模板继续回复 | 无错误文案；回复来自模板而非模型 | `LlmResilienceTest` |
| 真实 Embedding | 5000ms / 15000ms（默认值，`application.yml` 未覆盖） | **0** | 本地字符 bigram 哈希向量（256 维），**进程内永久锁存** | 无错误文案；RAG 召回质量下降 | `EmbeddingResilienceTest` |
| Redis 限流计数 | Lettuce 命令超时（未配置 → Lettuce 默认 60s）；连接不可达时立即失败 | **0** | fail-open **放行**，`Retry-After` 至少 1s | 无错误文案；限流实际未生效（多实例额度不再共享） | `RedisResilienceTest` |
| S3 兼容对象存储 | AWS SDK 默认 2s / 30s；可用 `api-call-timeout-ms` 设总超时 | **3 次**（SDK 默认，可配 0） | 写/读转 `BusinessException(500)`；读 404 转 `BusinessException(404)`；`exists` 404 → `false` | 「照片保存失败，请重试」/「照片读取失败，请重试」/「照片文件不存在（可能已被清理）」 | `S3ResilienceTest` |

## 3. 逐依赖细节

### 3.1 高德 Web 服务（地理编码）

- 配置：`travel.amap.base-url`（默认 `https://restapi.amap.com`）、`travel.amap.connect-timeout-ms`（默认 2000）、`travel.amap.read-timeout-ms`（默认 5000）。URI 由 baseUrl 拼接，客户端内不再硬编码域名；测试据此把流量指向本地 WireMock。
- 重试：**0**（地理编码路径）。`AmapGeocodeClient.geocode` 单点失败直接返回 `null`，`tryBatch` 失败返回 `false` 后**退化为逐条单点查询**——这是降级策略，不是重试：一批最多 10 个地址，最坏情况下一次批量失败会追加最多 10 次单点请求。注意范围：POI 搜索 / 路径规划走另一个类 `AmapDataService`，它在高德 QPS 限流（infocode `10021`）时做 1 次退避重试（220~400ms 抖动），不属于本矩阵的测试范围。
- 降级链：`AmapGeocodeClient`（返回 null/空 Map）→ `CityGeoLocator.locateCity` 再退 OSM Nominatim；城市目录走 `OnlineCityService` 的 LLM + OSM 兜底，路径走 `RouteService` 的本地 haversine 估算。
- 用户提示：无专属文案（不向用户报错）。日志：`高德定位失败: {address} - {message}`、`高德批量定位失败，退回逐个查询`。
- 指标：`provider=amap`；429→`rate_limited`，5xx→`server_error`，坏 JSON / 空响应体 / `status=0`（如 `INVALID_USER_KEY`）→`bad_response`，超时→`timeout`，连不上→`connection_error`。批量数量对不齐也记一次 `bad_response`。
- 恢复语义：无锁存。下一次请求成功即恢复（测试用同一 `CityGeoLocator` 连续两次调用验证）。
- 测试覆盖（`AmapResilienceTest`，9 个用例）：200 正常（GCJ-02→WGS-84）、429、500、坏 JSON、`status=0`、读超时（服务端延迟 1500ms > 300ms 预算）、故障后恢复、批量 500 退化为逐条查询、批量数量不齐退化为逐条查询。

### 3.2 LLM（OpenAI 兼容）

- 配置：`travel.llm.base-url` / `connect-timeout-ms`（默认 5000）/ `read-timeout-ms`（默认 30000，`application.yml` 用 `LLM_CONNECT_TIMEOUT_MS` / `LLM_READ_TIMEOUT_MS` 覆盖）。
- 单次调用还可带独立读超时：`AgentBudget.chat` 在端到端预算（`travel.agent.budget.*`，默认 30s）开启时把读超时压到「配置值与剩余预算的较小者」，避免最后一个阶段越过 deadline。
- 重试：**0**。`OpenAiCompatibleLlmClient.chat` 失败即返回 `null`。
- 降级链：`OpenAiCompatibleLlmClient` → `null`；`DelegatingLlmClient` 在未配置/配额耗尽/真实调用失败时同样返回 `null`（分别记 `llm.calls{result=skipped|quota|failed}`）；上层 `AgentEngine`/`StructuredPlanner` 用 `MessageTemplates` 的模板化输出继续回复。
- 用户提示：无错误文案（`application.yml` 注释口径：「未配置或调用失败……自动降级为规则引擎，功能不受影响」）。`/api/system/status` 的 `llmVerified` 会如实显示最近一次真实调用失败（`LlmRuntimeStatus`）。
- 指标：`provider=llm`；429→`rate_limited`，5xx→`server_error`，空响应/无 `choices`/内容空白/坏 JSON→`bad_response`，超时→`timeout`。既有 `llm.calls{result}` / `llm.latency` 不受影响。
- 恢复语义：无锁存，下次调用成功即恢复。
- 测试覆盖（`LlmResilienceTest`，9 个用例）：200、429、500、坏 JSON、空内容、读超时、故障后恢复、`DelegatingLlmClient` 失败时返回 `null`（上层走规则兜底）、未启用时零外呼（`skipped`）。

### 3.3 真实 Embedding

- 配置：`travel.embedding.base-url` / `connect-timeout-ms`（默认 5000）/ `read-timeout-ms`（默认 15000）；`application.yml` 只覆盖 base-url / api-key / model。
- 重试：**0**。
- 降级链：`OpenAiCompatibleEmbeddingService` → `LocalHashEmbeddingService`（256 维 bigram 哈希）。**降级是进程内锁存**：第一次失败后不再请求真实端点，避免同一知识库混用两套向量空间导致余弦相似度归零。
- 用户提示：无错误文案（`application.yml` 注释：失败「进程内锁定降级为本地字符 bigram 哈希」）。
- 指标：`provider=embedding`；reason 映射同上；空 `data` 也按 `bad_response`。
- 恢复语义：**本实例不自动恢复**。服务端恢复后，重新部署/重启进程才会重新使用真实模型；切换模型还需清空 `rag_document`/`rag_chunk` 重建知识库。测试同时验证「锁存」与「新实例可用真实模型」两面。
- 测试覆盖（`EmbeddingResilienceTest`，7 个用例）：200（L2 归一化向量）、429、500、坏 JSON、空 `data`、读超时、锁存 + 新实例恢复。

### 3.4 Redis 限流计数

- 启用条件：`travel.rate-limit.store=redis`（默认 `memory`）。
- 超时预算：未在 `application.yml` 配置 `spring.data.redis.timeout`，即沿用 Lettuce 默认命令超时（60s）；连接不可达时命令立即失败（connection refused），不会等满超时。降级测试用 127.0.0.1:1 + 200ms 命令/连接超时验证真实不可达路径。
- 重试：**0**（Lettuce 的 auto-reconnect 只作用于已建连接，不是业务重试）。
- 降级链：`tryAcquire` 返回 `true`（**fail-open 放行**）；`retryAfterSeconds` 返回至少 1 秒，避免 `Retry-After: 0` 引发客户端立刻重试放大压力。
- 用户提示：无错误文案；后果是限流实际未生效（多实例部署时配额不再全局共享），必须靠告警发现。
- 指标：既有 `ratelimit.failopen` + 统一口径 `external.degraded{provider=redis}`（连接被拒→`connection_error`，命令超时→`timeout`）。
- 恢复语义：无锁存，Redis 恢复后下一次命令成功即恢复真实限流（测试用「第一次抛异常、第二次返回计数」覆盖）。
- 测试覆盖（`RedisResilienceTest`，4 个用例）：真实端口不可达 fail-open（两个指标都递增、不抛异常、可重复调用）、`retryAfterSeconds` 至少 1s、命令超时归类 `timeout`、恢复后计数重新生效。

### 3.5 S3 兼容对象存储

- 启用条件：`travel.photos.store=s3`。
- 超时预算：默认沿用 AWS SDK 默认（连接 2s / socket 30s，已核对 AWS SDK 2.25.70 的 `SdkHttpConfigurationOption` 默认值）；如需单次调用总超时，设 `travel.photos.s3.api-call-timeout-ms`（毫秒，默认 `null` = 不设）。
- 重试：**SDK 默认 3 次**（legacy 重试模式）；`travel.photos.s3.max-retries` 可覆盖，`0` 表示关闭重试。降级测试设为 0，避免一个 5xx 被重试三次拖长用例。
- 降级/错误语义（沿用既有实现）：
  - `store` 失败 → `BusinessException(500, "照片保存失败，请重试")`；
  - `read` 命中 404（`NoSuchKeyException`）→ `BusinessException(404, "照片文件不存在（可能已被清理）")`，其它运行时异常 → `BusinessException(500, "照片读取失败，请重试")`；
  - `exists` 404 → `false`（不存在属正常业务结果，不记降级）；其它异常**继续上抛**，由 Controller 统一转 500，同时记一次降级指标；
  - `deleteQuietly` 吞掉异常只告警（清理失败不应阻塞删除主流程），也记一次降级指标。
- 指标：`provider=s3`；5xx→`server_error`，429→`rate_limited`，超时（含 `ApiCallTimeoutException`）→`timeout`，连接/DNS 失败→`connection_error`。
- 恢复语义：无锁存，下一次调用成功即恢复。
- 测试覆盖（`S3ResilienceTest`，9 个用例）：put 成功、put 5xx→500、put 超时→500、故障后恢复、head 404→false、head 5xx 上抛并记指标、read 404→404、read 5xx→500、端口不可达→500（`connection_error`）。

## 4. 本地如何运行

```bash
cd travel-agent-server
# 只跑降级矩阵（5 个类，38 个用例；WireMock 直接用已声明的 test scope 依赖，无需 Docker/Redis/S3）
mvn -B -Dtest='*ResilienceTest' test
# 离线环境（依赖已在本地仓库时）
mvn -B -o -Dtest='*ResilienceTest' test
# 单个依赖
mvn -B -Dtest=AmapResilienceTest test
```

- 全部用例不依赖外网：高德/LLM/Embedding/S3 指向本地 WireMock 动态端口，Redis 指向 127.0.0.1:1（不可达端口）。
- 超时用例统一用「服务端固定延迟 1500ms / 客户端 300ms 预算」制造真实读超时，单用例在秒级。
- 指标断言用 `SimpleMeterRegistry`（`DegradedMetrics` 工具类），不需要启动 Spring 上下文。
- 改完客户端后建议同时跑 `mvn -B test` 确认不破坏既有 461 个用例基线。

## 5. 已知边界与遗留风险

1. Embedding 的「永久降级」是刻意设计（向量空间一致性），代价是**故障恢复必须重启进程**；滚动重启期间新旧实例可能同时写两套向量空间，切换模型时务必清空并重建知识库。
2. Redis fail-open 期间限流形同不存在；如果 Redis 长时间不可用，只能靠 `external.degraded{provider=redis}` / `ratelimit.failopen` 告警发现，而不是靠用户报障。
3. 高德批量失败会放大请求数（1 次批量 + 最多 10 次单点）；对端 5xx 期间这会让 QPS 压力进一步上升，属于现有策略的已知代价。
4. S3 `exists` 遇到非 404 异常仍向上抛（既有语义），因此不存在「探测失败即当作不存在」的静默风险，但调用方必须处理 500。
5. 测试覆盖的是 HTTP 层故障（WireMock 注入的状态码/延迟/坏包体）与端口不可达，**不覆盖**真实 TLS 握手失败、DNS 劫持、代理层截断等网络故障；这些只能靠线上指标与 Runbook 兜底。
6. 本文的指标只包含 `external.degraded`；业务结果类指标（`travel.plan.outcome`、`travel.llm.tokens` 等）见 `docs/guide/observability.md`，两边不重名。