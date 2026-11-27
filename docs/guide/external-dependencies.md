# 外部依赖降级矩阵（高德 / LLM / Embedding / 天气 / 发票 OCR / Redis / S3）

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.16 / AWS SDK 2.25.70 / WireMock 3.13.1） | 最后验证：2026-11-23 | 事实来源：`travel-agent-server/src/main/java/com/travel/agent/{service,llm,rag,security,config}/*.java`、`src/main/resources/application.yml`、`src/test/java/com/travel/agent/{service,resilience}/*`

本文只写**代码与测试能证明的现状**：每个外部依赖的超时预算从哪来、重试到底有没有、
失败后变成什么、用户看到什么、哪个指标会涨、哪个测试类守住它。
没有重试的地方一律写 0，不把「将来要做的重试」写进现状。

## 1. 降级原则与指标契约

外部依赖总体遵循：**失败即降级，不把异常抛给业务主链路**。
两个例外：

- **S3 的写入/读取**：照片是用户资产，必须给用户明确的 500/404 语义，而不是静默丢文件。
- **天气**（2026-11-23 起）：产品路径**没有模拟兜底**。拿不到真实预报就是「不可用」——依赖天气的行程修改
  直接 `503` 拒绝并说明原因，普通规划如实写「未做天气校验」。理由见 [能力边界](capability-boundaries.md) §3。

统一降级指标（由本矩阵负责埋点，可观测性文档只消费、不重复定义）：

```
external.degraded{provider=amap|llm|embedding|weather|ocr|redis|s3,
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
| 天气（Open-Meteo） | 3000ms / 5000ms（`WeatherService` 硬编码） | **0** | **不降级、不模拟**：返回 `WeatherLookup.unavailable(reason)`；依赖天气的修改被明确拒绝（`status=FAILED`，业务码 503），普通规划如实标注未校验 | 拒绝文案含日期与原因；方案警告写「未获取真实天气（原因），本次未做雨天/户外校验」 | `WeatherServiceTest`、`WeatherDependentModificationTest` |
| 发票 OCR（百度云，默认关闭） | 3000ms / 5000ms（`OcrProperties` 默认值） | **0** | **不降级、不模拟**：识别不可用时 `InvoiceLookup(available=false, reason)`，「报销这张发票」不生成方案；`external.degraded{provider=ocr}` 只记客户端调用失败 | 「OCR 发票识别未配置（…）」/「发票识别失败（原因）。没有生成记账方案；可以重拍或手动记账。」 | `BaiduOcrClientTest`（解析与错误码）、`InvoiceOcrFlowTest`（未配置/失败/成功落账，脚本替身） |
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
- 花费口径（第二阶段）：`AgentBudget.chat` 同时是**唯一的 LLM 花费记账口**——调用次数与响应 `usage` 里的 token 计入 `AgentSpend`，
  单项到顶即拒绝下一次调用并按降级语义收尾（`travel.agent.spend`）。响应没有 `usage` 时不记 token（不估算）；
  费用上限只在 `LLM_PROMPT_PRICE_PER_1K_USD` / `LLM_COMPLETION_PRICE_PER_1K_USD` 配了真实单价时才可能触发。
- 重试：**0**。`OpenAiCompatibleLlmClient.chat` 失败即返回 `null`。
- 降级链：`OpenAiCompatibleLlmClient` → `null`；`DelegatingLlmClient` 在未配置/配额耗尽/真实调用失败时同样返回 `null`（分别记 `llm.calls{result=skipped|quota|failed}`）；上层 `AgentEngine`/`StructuredPlanner` 用 `MessageTemplates` 的模板化输出继续回复。
- 用户提示：无错误文案（`application.yml` 注释口径：「未配置或调用失败……自动降级为规则规划器 + 模板回复：主流程仍可跑通，但能力弱于模型」）。`/api/system/status` 的 `llmVerified` 会如实显示最近一次真实调用失败（`LlmRuntimeStatus`）。
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

### 3.4 天气（Open-Meteo）

- 配置：`travel.weather.simulation`（`WEATHER_SIMULATION`，默认 `false`）、`failure-cache-seconds`（`WEATHER_FAILURE_CACHE_SECONDS`，默认 60）、`geocode-base-url` / `forecast-base-url`（默认 Open-Meteo，测试用 WireMock 指向本地）。
- 超时：`SimpleClientHttpRequestFactory` 硬编码 connect 3000ms / read 5000ms。
- 重试：**0**。失败直接进入负缓存（同城同日 60 秒内不再外呼）。
- 数据语义：`WeatherService.forecast(city, date)` 返回 `WeatherLookup`——要么 `available=true`（`info.source=real`），
  要么 `available=false` + `reason`（`outside-forecast-window` / `city-not-found` / `timeout` / `rate_limited` /
  `server_error` / `bad_response` / `connection_error`）。**没有第三种「猜一个天气」的结果**。
- 产品行为：
  - 依赖天气的行程修改（消息含雨/天气等）且不可用 → `BusinessException(503)`，不生成方案；
  - 普通规划不可用 → 方案照常生成，审核警告如实写「未做雨天/户外校验」，文案不写「因为下雨」；
  - 模拟数据只在 `travel.weather.simulation=true`（仅测试/离线演示）下出现，`/api/system/status` 自报 `weatherProvider=simulated`。
- 用户提示：拒绝文案含城市、日期与原因（原文见 [能力边界](capability-boundaries.md) §3）；普通规划警告每天一条、有上限。
- 指标：`provider=weather`；reason 与统一词表一致（`timeout` / `rate_limited` / `server_error` / `bad_response` / `connection_error`）。
- 恢复语义：无锁存；负缓存到期后的下一次成功调用即恢复 `weatherProvider=open-meteo`。
- 测试覆盖：`WeatherServiceTest`（模拟开关、不可达、超窗口、WireMock 200/500/坏 JSON、负缓存与指标）、`WeatherDependentModificationTest`（依赖天气的修改被 503 拒绝且不落待确认方案）。

### 3.4.1 发票 OCR（百度云，默认关闭）

- 启用条件：`travel.ocr.provider=baidu`（`OCR_PROVIDER=baidu`）且 `OCR_BAIDU_API_KEY` / `OCR_BAIDU_SECRET_KEY` 都非空。
  判定唯一入口是 `OcrProperties#usable()`；不满足即「未配置」，**不会发起任何外呼**。
- 调用形态：先 `POST {endpoint}/oauth/2.0/token`（client_credentials）取 access_token，进程内缓存并按 `expires_in`
  提前 60 秒过期；再 `POST {endpoint}/rest/2.0/ocr/v1/vat_invoice`（`application/x-www-form-urlencoded`，`image=<base64>`）。
  地址可由 `travel.ocr.baidu.endpoint` 覆盖（私有代理/测试指向本地）。
- 超时：`SimpleClientHttpRequestFactory`，连接 3000ms / 读 5000ms（`OcrProperties` 默认值，无环境变量占位）。
- 重试：**0**。失败即返回「不可用 + 原因」，由用户在对话里决定重拍或手动记账。
- 不降级、不模拟：没有「猜一份识别结果」的分支。金额缺失（或 ≤0）单独判为「没有识别出金额」，
  同样不生成方案；这种情况属于图片质量问题，**不计入** `external.degraded`（与天气的「输入边界不记指标」同一口径）。
- 用户提示：`InvoiceCommandHandler` 的三段文案（未上传 / 未配置 / 识别失败）原文见
  [能力边界](capability-boundaries.md) §1.2 与 [接口语义](api-reference.md) §5.2；全部是 `status=COMPLETED`，不是 500。
- 指标：`provider=ocr`；reason 复用统一词表（HTTP 429→`rate_limited`、5xx→`server_error`、超时→`timeout`、
  连不上→`connection_error`、其余（含百度 `error_code` 非 0）→`bad_response`）。
- 恢复语义：无锁存；下次调用成功即恢复（access_token 过期会重新获取）。
- 测试覆盖：`BaiduOcrClientTest`（纯单测：价税合计优先、`TotalAmount` 回落、中文/横线日期、`error_code` 非 0、空响应）；
  `InvoiceOcrFlowTest`（脚本化 `OcrClient` 替身：未配置 / 识别失败 / 成功经确认落账 / 无候选不关联 / 工具只读）。
  **真实百度识别没有验证**：缺 Key，识别率、配额与真实响应字段差异均未测（见 §5 第 8 条）。

### 3.5 Redis 限流计数

- 启用条件：`travel.rate-limit.store=redis`（默认 `memory`）。
- 超时预算：未在 `application.yml` 配置 `spring.data.redis.timeout`，即沿用 Lettuce 默认命令超时（60s）；连接不可达时命令立即失败（connection refused），不会等满超时。降级测试用 127.0.0.1:1 + 200ms 命令/连接超时验证真实不可达路径。
- 重试：**0**（Lettuce 的 auto-reconnect 只作用于已建连接，不是业务重试）。
- 降级链：`tryAcquire` 返回 `true`（**fail-open 放行**）；`retryAfterSeconds` 返回至少 1 秒，避免 `Retry-After: 0` 引发客户端立刻重试放大压力。
- 用户提示：无错误文案；后果是限流实际未生效（多实例部署时配额不再全局共享），必须靠告警发现。
- 指标：既有 `ratelimit.failopen` + 统一口径 `external.degraded{provider=redis}`（连接被拒→`connection_error`，命令超时→`timeout`）。
- 恢复语义：无锁存，Redis 恢复后下一次命令成功即恢复真实限流（测试用「第一次抛异常、第二次返回计数」覆盖）。
- 测试覆盖（`RedisResilienceTest`，4 个用例）：真实端口不可达 fail-open（两个指标都递增、不抛异常、可重复调用）、`retryAfterSeconds` 至少 1s、命令超时归类 `timeout`、恢复后计数重新生效。

### 3.6 S3 兼容对象存储

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
# 只跑降级矩阵（*ResilienceTest 5 个类 + 天气两个测试类 + 发票 OCR 两个测试类；WireMock 直接用已声明的 test scope 依赖，无需 Docker/Redis/S3）
mvn -B -Dtest='*ResilienceTest,WeatherServiceTest,WeatherDependentModificationTest,InvoiceOcrFlowTest,BaiduOcrClientTest' test
# 离线环境（依赖已在本地仓库时）
mvn -B -o -Dtest='*ResilienceTest,WeatherServiceTest,WeatherDependentModificationTest,InvoiceOcrFlowTest,BaiduOcrClientTest' test
# 单个依赖
mvn -B -Dtest=AmapResilienceTest test
```

- 全部用例不依赖外网：高德/LLM/Embedding/天气/S3 指向本地 WireMock 动态端口，Redis 指向 127.0.0.1:1（不可达端口）。
- 超时用例统一用「服务端固定延迟 1500ms / 客户端 300ms 预算」制造真实读超时，单用例在秒级。
- 指标断言用 `SimpleMeterRegistry`（`DegradedMetrics` 工具类），不需要启动 Spring 上下文。
- 改完客户端后建议同时跑 `mvn -B test` 确认不破坏既有用例基线（用例数以 CI 输出为准）。

## 5. 已知边界与遗留风险

1. Embedding 的「永久降级」是刻意设计（向量空间一致性），代价是**故障恢复必须重启进程**；滚动重启期间新旧实例可能同时写两套向量空间，切换模型时务必清空并重建知识库。
2. Redis fail-open 期间限流形同不存在；如果 Redis 长时间不可用，只能靠 `external.degraded{provider=redis}` / `ratelimit.failopen` 告警发现，而不是靠用户报障。
3. 高德批量失败会放大请求数（1 次批量 + 最多 10 次单点）；对端 5xx 期间这会让 QPS 压力进一步上升，属于现有策略的已知代价。
4. S3 `exists` 遇到非 404 异常仍向上抛（既有语义），因此不存在「探测失败即当作不存在」的静默风险，但调用方必须处理 500。
5. 测试覆盖的是 HTTP 层故障（WireMock 注入的状态码/延迟/坏包体）与端口不可达，**不覆盖**真实 TLS 握手失败、DNS 劫持、代理层截断等网络故障；这些只能靠线上指标与 Runbook 兜底。
6. 本文的指标只包含 `external.degraded`；业务结果类指标（`travel.plan.outcome`、`travel.llm.tokens` 等）见 `docs/guide/observability.md`，两边不重名。
7. 天气的「不可用」是产品语义而不是降级：离线部署 + 未开 `WEATHER_SIMULATION` 时，所有依赖天气的修改都会 503。这是刻意的（宁可不排，也不拿模拟数据改行程），部署前应确认出站 HTTPS 可达 Open-Meteo。
8. 发票 OCR 的**真实识别质量没有验收**（缺百度云 Key，2026-11-27）：只验证了响应解析与「未配置/失败不生成方案」两条路径。
   开启前需用真实票面自测：金额取价税合计还是合计金额、商户/日期字段是否与本实现一致、百度侧限额与计费。
   另外 token 请求按百度文档用 query 参数传 client_id / client_secret——本实现从不把异常原文（含 URL）写进日志或用户文案（R7），
   但真实链路上的抓包/代理日志仍可能记录该 URL，属于部署侧责任。