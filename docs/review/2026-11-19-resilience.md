# 韧性测试报告（V2.2）：并发 / 慢接口 / 第三方失败 / 长时间运行

> 快照日期：2026-11-19 ｜ 验证范围：并发、慢接口、第三方失败、约 12 分钟长跑（后端 jar，不含前端） ｜ 结论：总判定可接受（无 5xx / 挂死 / 串号）；P1 慢依赖无端到端预算、P2 无效 Key 自报失真需整改（详见 §1）

- **测试日期**：2026-11-19（14:20–14:49，Asia/Shanghai）
- **被测对象**：`travel-agent-server-0.1.0.jar`（构建于 2026-11-19 14:15:49），前端不在本次范围内
- **负责范围**：并发、慢接口、第三方服务失败、长时间运行（其余验收项不在本报告）
- **数据来源**：全部为真实 HTTP 请求；原始结果 `.evidence/reports/resilience.json`；脚本 `.e2e/resilience/`
- **原则**：不修改产品源码；不杀/不重启 18090/18091/18092；长跑与并发一律用临时账号，不动验收账号 `demo` 的数据

---

## 1. 结论摘要

| 测试项 | 结论 | 关键数字 |
| --- | --- | --- |
| 1. 并发（20 路 × 2 轮 + 10 路 runs × 2 轮） | **通过** | 40/40 规划成功、20/20 runs 成功；chat p50 **47.4ms** / p95 **60.9ms** / max 62.3ms；0 个 5xx、0 次超时、0 处数据串号 |
| 2. 慢接口（LLM 睡 35s > 30s 读超时） | **功能通过，存在设计问题** | 单次 LLM 调用被截断在 **30.03s**（读超时 30s）；规划请求仍 200 并降级规则路径；但**一次规划端到端 128.1s**（4×30s + 1×8s），无总预算/熔断 |
| 3. 第三方失败（真实 402 / 替身 402 / 500 / 连接拒绝 / 高德） | **5 项通过 + 1 项复核** | 全部请求 200 且降级成功；失败计数 `llm.calls{result=failed}` 与日志一致；**高德 Key 无效时能力自检失真**（报 amap、实际全 estimated） |
| 4. 长时间运行（11.9 分钟，每 5s 一轮 4 类请求） | **通过** | 576/576 成功、0 失败、0 5xx；p95 稳定在 **7.1–10.2ms**；RSS 起 408MB → 终 416.1MB（+2%，回归斜率 **−3.4MB/min**）；线程 84→86（区间 84–87）；H2 +1.6MB；`/actuator/health` 结束仍 **UP** |

**总判定：可接受。** 四项功能在异常依赖下都没有失败、没有 5xx、没有线程挂死、没有数据串号。
但发现 5 个真实问题（见 §7），其中 **P1「慢依赖无端到端预算」** 与 **P2「高德无效 Key 自报不准」** 建议列入整改。

---

## 2. 环境与测试边界

### 2.1 实例与端口

| 端口 | 角色 | 本次是否重启 | 用途 |
| --- | --- | --- | --- |
| 18090 | 主实例（LLM 关闭、限流关闭、无 `AMAP_KEY`、H2 文件库 `.evidence/h2-a`） | 否 | 并发测试、长跑、高德「无 Key」对照 |
| 18091 | 真实 DeepSeek Key（余额 0 → 402） | 否 | 「真实第三方 402」证据 |
| 18092 / 18099 | LLM 替身链路（验收方既有） | 否 | 仅做过一次状态探测（确认 `llmConfigured`），未用于本测试流量 |
| **18100** | **自建实例**（独立端口 + 独立 H2 文件库 + 固定 JWT_SECRET + 限流关闭） | 是 | 慢依赖、402/500、连接拒绝、无效高德 Key |
| **18110** | **自建 OpenAI 兼容替身**（立即应答 / 睡 N ms / 返回 402 / 500） | 是 | 确定性制造「慢」与「错」 |

两个自建进程测试结束后已全部停止并释放端口（`netstat` 确认 18100/18110 无 LISTENING；无残留 java/node）。

### 2.2 为什么需要自建实例 + 替身

18090 的 LLM 是关闭的（规则引擎），无法验证「模型慢/模型报错」；18091 只能提供 402 一种错误，
且无法把延迟调到「跨过 30s 读超时」。替身只替换**模型端的 HTTP 应答**，
后端仍然走真实的 `OpenAiCompatibleLlmClient → HTTP → 解析 → DelegatingLlmClient → 规则降级` 全链路，
因此观察到的超时、降级、日志、线程行为都是产品代码本身的行为。

### 2.3 数据隔离

新建临时账号（`resil…` / `slowllm…` / `concat…` / `soak…` / `amap…` / `tp…` 前缀）承载所有写操作；
`demo` 只用于读取 `/api/system/status`、`/actuator/metrics` 与在 18091 上做 402 验证（只读+规划，不提交确认）。

### 2.4 局限（如实说明）

1. 18090 是共享实例。并发测试窗口内 `http.server.requests` 计数从 333 → 398（+65），与本脚本
   40 chat + 20 runs + 5 次登录/状态/指标 = 65 完全吻合，说明该窗口内没有第三方流量混入；
   长跑窗口（11.9 分钟）未做此对齐，若期间有其它脚本打 18090，会抬高个别样本。
2. 慢依赖只覆盖 LLM 与高德两类第三方（天气/OCR/MCP 未纳入）。
3. 并发只做 20 路（Tomcat 默认 `max-threads=200`、Agent 异步池 4/16/100），未做饱和压测。
4. 分位数采用 nearest-rank（返回值必来自样本本身），窗口样本量已在表中标注。

---

## 3. 测试 1：并发

**方法**：同一临时账号，2 轮；每轮 20 个 `POST /api/agent/chat` 同时发出（消息模板
`杭州两日游，两个人，预算{2000+轮次*100+i*10}元`，预算逐条不同），紧接 10 个并发
`GET /api/agent/runs?limit=20`。两轮之间间隔 3s。客户端超时 120s。

### 3.1 结果

| 轮次 | chat 数 | 成功 | HTTP 状态分布 | chat 耗时 p50 / p95 / max | runs 数 | 成功 | runs p50 / p95 / max | 20 并发墙钟 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 第 1 轮 | 20 | 20 | 200×20 | 58.9 / 61.1 / 62.3 ms | 10 | 10 | 5.6 / 7.4 / 7.4 ms | 66 ms |
| 第 2 轮 | 20 | 20 | 200×20 | 40.6 / 45.7 / 47.4 ms | 10 | 10 | 13.2 / 15.0 / 15.0 ms | 48 ms |
| **合计** | **40** | **40** | **200×40** | **47.4 / 60.9 / 62.3 ms** | **20** | **20** | **7.4 / 14.1 / 15.0 ms** | — |

超时 0 次、5xx 0 个、`http.server.requests{status=500}` 计数器不存在（= 服务端从未产生 5xx）。

### 3.2 一致性校验（数据串号检测）

| 校验项 | 方法 | 结果 |
| --- | --- | --- |
| planId 唯一 | 40 个响应 planId 去重 | 40 个，重复 0 |
| 预算回显一致 | 每条回复必须含自己的 `¥{预算}`，且不得出现同轮别人的预算 | 不一致 0、串漏 0 |
| tripId 异常 | 新建方案阶段 `tripId` 应为 null | 异常 0 |
| 运行记录完整 | 事后 `GET /runs?limit=60` 回查 | 期望 40 条，命中 40 条，`runId` 唯一 40，状态全 `SUCCEEDED`，消息与请求逐条对应，**无一条串到别人的消息** |
| 运行耗时 | 40 条 run 的 `durationMs` | p50 36ms / p95 43ms / max 43ms |

**结论：通过。** LLM 关闭时，20 路并发的规划请求与 runs 查询都没有 5xx、没有超时、
没有 planId/tripId/预算串号；H2 单写者也没在 20 路并发下产生锁等待失败。

---

## 4. 测试 2：慢接口（慢 LLM 下的超时与降级）

**拓扑**：自建实例 18100（`LLM_ENABLED=true`、`LLM_READ_TIMEOUT_MS=30000`）
→ `LLM_BASE_URL=http://127.0.0.1:18110/v1`（替身）。替身有三种应答：立即 200（内容 `{}`）/
睡 20s / 睡 35s。后端每次都真的发出 HTTP 请求，超时由后端自己判定。

### 4.1 场景与耗时

| 场景 | 替身行为 | 请求数（LLM 调用数） | 客户端耗时 | 业务结果 | 耗时相对正常 |
| --- | --- | --- | --- | --- | --- |
| A 正常依赖·轻量问答 | 立即 200 | 3 次（各 1） | **p50 24.4ms** | 200 / `CHITCHAT` | 基线 |
| A 正常依赖·完整规划 | 立即 200 | 2 次（各 8 次 LLM 调用） | **p50 130ms** | 200 / `PLAN_TRIP` | 基线 |
| B 慢但未超时·轻量（睡 20s < 30s） | 睡 20s 后 200 | 1 次 | **20.02s** | 200 / `CHITCHAT` | +20s（未误判失败） |
| C 慢超时·轻量（睡 35s > 30s） | 睡 35s，不返回 | 1 次 | **30.03s** | 200 / `CHITCHAT`（规则路径） | +30.0s ≈ 读超时 |
| D 慢超时·完整规划（睡 35s） | 睡 35s | 1 次请求，**5 次 LLM 调用** | **128.13s** | 200 / `PLAN_TRIP` / 有 planId | +128s |

**超时确实发生在传输层**：替身记录到那 1 次（场景 C）请求「客户端在应答前断开」，
断开时刻 = 到达后 **30.01s**；场景 D 的 5 次调用同样全部被客户端断开，逐次寿命为
**30.01s / 30.01s / 30.01s / 30.01s / 8.00s**（最后一次是文案润色的独立 8s 读超时），
与端到端 128.13s 完全对得上。也就是说后端的 `read-timeout-ms=30000` 是真正生效的
（不是等到 35s 自然返回），且不同用途的超时（30s 全局 / 8s 润色）各自独立生效。

**降级确实发生**：场景 C/D 的响应都是 200 + 正常业务结果（`CHITCHAT` 帮助文案 / `PLAN_TRIP`
的完整方案与 planId），没有 5xx、没有把异常抛给用户。

### 4.2 不挂死线程（慢请求进行中 + 依赖恢复后）

| 时点 | `/actuator/health` | `/api/agent/runs` | `/api/system/status` |
| --- | --- | --- | --- |
| 慢请求进行中（每 5s 探一次，共 6 次） | 6.2–11.9 ms，全部 UP | 5.4–18.8 ms，全部 200 | 2.3–3.7 ms，全部 200 |

| 恢复后指标 | 值 |
| --- | --- |
| 依赖恢复正常后连发 5 个请求 | **5/5 成功**，p50 21.6ms / max 29.1ms |
| JVM 活跃线程 | 慢请求前 32 → 恢复后 35（peak 35） |
| Hikari 等待连接 | 0 |
| 日志降级告警 | 6 条（场景 C 1 条 + 场景 D 5 条） |

**结论：功能通过**——单次调用被读超时截断、请求整体仍成功、依赖恢复后服务立即恢复、没有线程/连接泄漏。
但有两个必须写进问题清单的观察：

1. **端到端没有总预算/熔断**：一次规划串行经过 5 个 LLM 阶段，每个阶段都要等满 30s 才降级，
   端到端 128.13s。慢模型下这条链路会长时间占住一个 Tomcat 线程（同步 `/api/agent/chat` 没有服务端超时），
   且**同一用户的下一个请求仍会重新等满 30s**（`DelegatingLlmClient` 没有连续失败后的冷却/熔断，
   对比：Embedding 侧 `OpenAiCompatibleEmbeddingService` 是「失败即进程内锁定降级」）。
2. **降级日志丢根因**：日志里只有
   `LLM 调用失败，降级为规则模板: Error while extracting response for type [...JsonNode] and content type [application/octet-stream]`，
   `log.warn` 只打印了异常 `getMessage()`，没有 cause（真正的 `SocketTimeoutException`），
   排障时无法区分「超时 / 解析失败 / 网络错」。

---

## 5. 测试 3：第三方服务失败

### 5.1 五个场景

| # | 场景 | 依赖端真实行为 | chat 结果 | 耗时 | 失败计数增量 | 降级日志 |
| --- | --- | --- | --- | --- | --- | --- |
| a1 | 真实第三方 402（18091） | DeepSeek 返回 402（余额 0） | 200 / `PLAN_TRIP` / 有 planId | 2.59s（5 次 402 往返） | `llm.calls{result=failed}` 11 → 17（**+6**），`result=ok` 计数**从未出现**（= 一次都没成功） | 18091 侧日志同级告警（本脚本未接管该实例日志） |
| a2 | 替身返回 402（18100） | HTTP 402 + `Insufficient Balance` | 200 / `PLAN_TRIP` / 有 planId | **95.7ms** | +5 | 5 条，含 `402 Payment Required ...` 与响应体原文 |
| a3 | 替身返回 500（18100） | HTTP 500 | 200 / `PLAN_TRIP` / 有 planId | **94.3ms** | +5 | 5 条，含 `500 Internal Server Error ...` |
| b | 连接拒绝（18100 → 未监听端口 18110） | 端口无监听（`ECONNREFUSED`） | 200 / `PLAN_TRIP` / 有 planId | **68.6ms** | +5 | 5 条，含 `Connection refused` |
| c | 高德 Key 无效（18100，`AMAP_KEY=INVALID_RESILIENCE_TEST_KEY_0001`） | 高德 REST 返回 HTTP **200** + `{"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}` | 200 / `PLAN_TRIP`，确认后 tripId=1 | 958.9ms（含 7 段真实高德往返） | — | **0 条**（见 §7 P2） |

补充：用同一台主机直连 `restapi.amap.com`，无效 Key 确实返回上述业务错误体
（HTTP 200 + `status=0`），与本实例观察到的行为一致。

### 5.2 高德「能力自检」对照（关键差异）

| 状态 | `/api/system/status` 的 `routeProvider` / `routeHint` | `/api/trips/{id}/route-legs` 实际 `source` | 自洽？ |
| --- | --- | --- | --- |
| 18090：**未配置** `AMAP_KEY` | `amapConfigured=false`、`geoProvider=osm`、**`routeProvider=estimated`**、提示「路线为本地距离估算」 | 7/7 段 `estimated` | ✅ 一致（失败如实自报家门） |
| 18100：**配置了无效** Key | `amapConfigured=true`、`geoProvider=amap`、**`routeProvider=amap`**、提示「路线使用高德真实路径规划」 | 7/7 段 `estimated` | ❌ **不一致：状态页宣称走真实路径，实际全部是估算** |

**结论：a/b（LLM 各类失败）5 个场景全部通过**——第三方失败不会把错误抛给用户，
规划仍成功、快速降级（错误响应 68–96ms，真实 402 约 2.6s），失败计数与日志都对得上。
**c（高德）在「无 Key」下通过，在「无效 Key」下暴露自检失真，判 REVIEW**（详见 §7 P2）。

---

## 6. 测试 4：长时间运行

**方法**：对 18090 连续 **716.2s（11.9 分钟）**，每 5s 一轮，每轮顺序发 4 个请求
（chat 轻量问答 / 行程列表 / runs / health），共 144 轮 576 个请求，全部使用临时账号；
chat 消息在 `你好`、`谢谢`、`北京有哪些必去景点？`、`两个人去成都玩三天怎么安排比较好？` 之间轮换
（后两条分别落到 `QUERY_KNOWLEDGE` 与 `PLAN_TRIP`，按任务要求只产生待确认方案、不提交确认）。
进程 RSS / 线程数 / H2 文件大小由独立采样器每 5s 记录一次（目标 pid = 18090 监听进程 44612）。

### 6.1 请求总量

| 指标 | 值 |
| --- | --- |
| 实际时长 | 716.2s（11.9 分钟；脚本按要求 12 分钟，误差 < 1%，如实记录） |
| 请求总数 | **576**（chat 144 / trips 144 / runs 144 / health 144） |
| 成功 | **576**（HTTP 状态全部 200） |
| 失败 | **0**（未预期失败 0、网络失败 0） |
| 5xx | **0** |
| 全量耗时 | min 1.5 / p50 **3.7** / p90 7.3 / p95 **8.6** / p99 12.8 / max 187.9 ms |

分类 p50 / p95 / max：chat 6.5 / 11.8 / 187.9ms；trips 2.7 / 4.6 / 5.3ms；runs 2.6 / 4.4 / 5.8ms；health 4.2 / 7.9 / 10.2ms。

### 6.2 p95 随时间（2 分钟窗口，每窗口 96 个请求）

| 窗口 | 0–2min | 2–4min | 4–6min | 6–8min | 8–10min | 10–12min |
| --- | --- | --- | --- | --- | --- | --- |
| p95 (ms) | 8.6 | 8.4 | 10.2 | 8.4 | 9.2 | 7.1 |
| p50 (ms) | 4.2 | 4.2 | 4.2 | 3.1 | 3.7 | 3.0 |

无随时间恶化趋势（末窗口比首窗口还低）。

### 6.3 进程 RSS / 线程 / H2（2 分钟窗口，共 145 个采样）

| 窗口 | 0–2min | 2–4min | 4–6min | 6–8min | 8–10min | 10–12min |
| --- | --- | --- | --- | --- | --- | --- |
| RSS min (MB) | 410.2 | 431.9 | 430.2 | **349.0** | 361.9 | 406.9 |
| RSS 均值 (MB) | 424.1 | 435.1 | 433.4 | 397.6 | 383.7 | 411.5 |
| RSS max (MB) | 431.9 | 438.1 | 438.3 | 432.4 | 408.1 | 415.5 |

| 指标 | 起点 | 终点 | 区间 | 斜率 / 增长 |
| --- | --- | --- | --- | --- |
| RSS | **408.0 MB** | **416.1 MB** | 349.0–438.3 MB | +8.1 MB（+2.0%）；全程逐点回归 **−3.4 MB/min**；2 分钟窗口均值回归 **−3.6 MB/min** |
| 线程数 | 84 | 86 | 84–87 | 斜率 0/min（无爬升） |
| H2 文件 | 2,007,040 B | 3,715,072 B | — | **+1.6 MB**（≈143 KB/min，主要是 run/task/tool_call 追踪行，属数据增长） |
| `/actuator/health` | UP | **UP**（结束时 4.4ms） | — | 全程 UP |

**RSS 曲线形态（判定的关键）**：0→5.5 分钟从 408MB 爬到 438MB 峰值（JVM 预热/堆扩张），
第 6.7→7.5 分钟一次 GC 回落到 349MB，之后回填到 416MB，属**典型 GC 锯齿**而非单调暴涨：
后段峰值（408–416MB）低于前段峰值（438MB），谷底也在下降（410→349MB）。
故判「RSS 无单调暴涨」，不以「后一半斜率 +12.5MB/min」为据（那是 GC 后回填相，不是泄漏）。

**结论：通过。** 12 分钟、576 个真实请求：0 失败、0 5xx、p95 稳定在 7–10ms、
线程数稳定、health 始终 UP、RSS 无单调上涨、H2 仅增长 1.6MB 且增速与写入量匹配。

---

## 7. 发现的问题与建议（按优先级）

### P1 慢依赖没有端到端预算，也没有熔断（建议整改）

- **现象/证据**：LLM 每阶段读超时 30s 生效，但一次规划串行 **5 个** LLM 阶段 → 端到端
  **128.13s**（4×30s 读超时 + 1×8s 润色超时，日志告警时间间隔正好 30s、最后一条 +8s）。
  同步接口 `POST /api/agent/chat` 没有任何服务端超时；`SseEmitter` 的 180s 只覆盖 SSE 路径。
- **影响**：模型抖动/挂起时，单个规划占住 1 个 Tomcat 线程 2 分钟以上；SSE 路径下
  `agentTaskExecutor`（core 4 / max 16 / queue 100）在同类慢请求叠加时会被占满；
  且后续请求**不会变快**（无「连续失败冷却」），故障期间每次请求都重复付出 30s×N 的等待。
- **建议**：①加「单次对话的 LLM 总预算」（如 45s），超出后直接走规则路径；
  ②在 `DelegatingLlmClient` 加连续失败计数 + 冷却窗口（例如 60s 内失败 3 次则 5 分钟内直接跳过模型），
  与 Embedding 侧的锁定降级思路保持一致；③对慢请求在响应/事件里标注已降级。

### P2 高德「能力自检」在 Key 无效时失真，且降级完全静默（建议整改）

- **现象/证据**：无效 Key 时 `/api/system/status` 返回 `amapConfigured=true`、`geoProvider=amap`、
  **`routeProvider=amap`** 并提示「路线使用高德真实路径规划」，而 `/route-legs` **7/7 段都是 `estimated`**。
  原因是 `RouteService.provider()` 只看 `AmapProperties.usable()`（Key 非空即真），不看真实调用结果；
  高德 REST 用「HTTP 200 + `status=0` 业务错误」表达无效 Key，`parseRoute` 返回 null 后
  既不抛异常也不打日志，于是**整条链路 0 条日志**。
- **影响**：用户/运维被明确告知「这是真实路径数据」，实际拿到的是 haversine 估算；
  FR-05「如实上报数据来源」只在「未配置」时成立，配置错误/额度耗尽/Key 过期时会撒谎。
- **建议**：①`provider()` 改为「配置 + 最近一次真实调用成功与否」的组合状态（失败即降级为
  `estimated` 并在 `routeHint` 说明原因）；②解析失败（`status != 1`）时打一条 WARN（带 `info/infocode`），
  至少让日志能自证。

### P3 降级日志丢根因（建议整改）

- **现象/证据**：读超时表现为
  `LLM 调用失败，降级为规则模板: Error while extracting response for type [...JsonNode] and content type [application/octet-stream]`。
  `log.warn("... {}", e.getMessage())` 只取 wrapper 的 message，`SocketTimeoutException` 这条根因丢失，
  且 message 里的 `application/octet-stream` 还会把人往「内容类型/解析」方向误导。
- **建议**：`log.warn("LLM 调用失败，降级为规则模板", e)`（或打印 `e + cause`），
  并在 message 前带上「超时/HTTP 状态/连接失败」的分类。

### P4 日志文件默认用平台字符集，中文 Windows 上是 GBK（建议整改）

- **现象/证据**：Spring Boot file appender 默认 `Charset.defaultCharset()`，实测日志文件为 GBK；
  按 UTF-8 采集时中文全部变成乱码（`LLM µ÷ÓÃÊ§°Ü…`），用 UTF-8 关键字检索「降级」会漏掉全部告警。
- **建议**：显式配置 `logging.charset.file: UTF-8`（容器/CI 采集统一 UTF-8 时尤其必要）。

### P5 用户侧看不到「模型已降级」（建议改进）

- **现象/证据**：18091 上 `llm.calls` 的**全部**调用都是 `result=failed`，但 `/api/system/status`
  仍返回 `llmConfigured=true` 与「已接入大模型：…走模型」。该字段只反映配置，不反映运行时健康。
- **建议**：状态接口补「最近 N 分钟 LLM 成功率 / 最近失败时间 / 是否处于降级模式」，
  前端据此显示「AI 暂时不可用，已用规则引擎」。

### 补充观察（不是缺陷，但值得知道）

- Agent 异步池是 **4/16/100**（`AsyncConfig`），而慢模型下单个 SSE 规划可占用线程 >2 分钟。
  16 个并发慢规划就会让池饱和、队列堆积，第 117 个请求被 `AbortPolicy` 拒绝（SSE 会收到
  「服务器繁忙，请稍后重试」）。本次未做该饱和压测，属代码推断，供容量评估参考。
- 慢依赖期间 `/actuator/health`、`/api/agent/runs`、`/api/system/status` 全部保持 2–19ms，
  说明慢 LLM 不会拖垮非 LLM 接口（连接池/线程隔离是有效的）。
- 20 路并发写 H2 未出现锁等待失败，`hikaricp.connections.pending=0`。

---

## 8. 复现方式与产物

### 8.1 脚本（`.e2e/resilience/`，Node 原生，无新依赖）

| 文件 | 作用 |
| --- | --- |
| `lib.mjs` | HTTP 计时、临时账号、分位数、并发、GBK 日志解码、指标取值 |
| `manage-instance.mjs` | 自建实例 18100 的 serve / stop / status |
| `stub-openai-llm.mjs` | OpenAI 兼容替身：`ok` / `slow(delayMs)` / `http402` / `http500` |
| `run-concurrency.mjs` | 测试 1 |
| `run-slow-llm.mjs` | 测试 2 |
| `run-third-party.mjs` | 测试 3（`--part=llm402-real / stub-errors / refused / amap`） |
| `run-soak.mjs` + `soak-proc.ps1` | 测试 4（请求循环 + 进程采样） |
| `analyze-soak.mjs` | 长跑后处理：合并采样与请求，算斜率/窗口 |
| `summarize.mjs` | 汇总四项结论到 `resilience.json` 顶层 `summary` |
| `README.md` | 端口约定与完整运行顺序 |

一键顺序（详见 `README.md`）：

```bash
node .e2e/resilience/stub-openai-llm.mjs                 # 后台
node .e2e/resilience/manage-instance.mjs serve           # 后台，:18100
node .e2e/resilience/run-concurrency.mjs
node .e2e/resilience/run-slow-llm.mjs
node .e2e/resilience/run-third-party.mjs --part=llm402-real
node .e2e/resilience/run-third-party.mjs --part=stub-errors
#   停替身（端口不监听）后
node .e2e/resilience/run-third-party.mjs --part=refused
AMAP_PORT=18090 AMAP_VARIANT=18090_no_key node .e2e/resilience/run-third-party.mjs --part=amap
#   用 --amap-key=INVALID... 重启自建实例后
AMAP_PORT=18100 AMAP_VARIANT=18100_invalid_key node .e2e/resilience/run-third-party.mjs --part=amap
node .e2e/resilience/run-soak.mjs --minutes=12 --interval=5000
node .e2e/resilience/analyze-soak.mjs
node .e2e/resilience/summarize.mjs
node .e2e/resilience/manage-instance.mjs stop
```

### 8.2 原始结果

- `.evidence/reports/resilience.json`：`summary`（四项结论与关键数字）、`concurrency`（每请求样本与校验）、
  `slowLlm`（四个场景 + 探针 + 日志证据）、`thirdParty`（5 个场景 + 高德两种 Key）、`soak`（576 条请求 +
  145 个进程采样 + 窗口统计）。分位数一律 nearest-rank。
- 自建实例日志：`.evidence/resilience-run/instance.log`（含降级告警原文）；
  长跑明细：`.evidence/resilience-run/soak-requests.jsonl`、`soak-proc.csv`。
- 长跑 RSS 以「ISO 时间戳」为准计算（采样器的 epoch 列曾因 PowerShell `DateTimeKind` 偏移 8 小时，
  `analyze-soak.mjs` 已改为解析 ISO；结论不受影响）。

### 8.3 清理确认

自建实例（java 18100）与替身（node 18110）均已停止；`netstat` 显示 18100/18110 无 LISTENING；
无残留 java/node 进程；18090/18091/18092/4173 未被重启或干扰。