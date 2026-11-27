# 可观测性：SLO、指标字典、告警与 Dashboard

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.16 / Micrometer Prometheus registry） | 最后验证：2026-11-25 | 事实来源：`travel-agent-server/src/main/java/com/travel/agent/observability/TravelMetrics.java`、`.../agent/AgentStageTimer.java`、`.../service/PlanOutboxService.java`、`.../service/AgentRunRecoveryJob.java`、`travel-agent-server/src/main/resources/application.yml`、`docs/operations/`

- **负责范围**：核心指标定义、SLO 与告警阈值、Prometheus 抓取、Grafana Dashboard、日志字段约定
- **关联文件**：[告警规则](../operations/alerts.yml) ｜ [Grafana Dashboard](../operations/grafana-travel-agent.json) ｜ [故障手册](../operations/runbook.md)
- **契约纪律**：本文件里的指标名与 tag 是对外契约。改名/改 tag 必须同时改：`TravelMetrics` 常量 → 本文件 → `alerts.yml` → Dashboard JSON → `*MetricsTest`。只改代码的后果是「面板静默变空、告警永不触发」。

一句话导读：先看 §2 的指标字典确认「能查到什么」，再看 §3 的 SLO 表确认「什么算坏」，最后按 §5 抓取、§4 告警与 [runbook](../operations/runbook.md) 处置。

---

## 1. 5 分钟上手

```bash
# 1) 登录取 JWT（除 /actuator/health 外，actuator 端点都需要认证）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

# 2) 拉指标（Prometheus 文本格式）
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/actuator/prometheus | grep -E '^(travel_|agent_stage|sse_disconnects|llm_calls|external_degraded)'

# 3) 只查单个指标（JSON，排障用）
curl -s -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:8080/actuator/metrics/travel.plan.outcome'
```

> `/actuator/health` 是唯一免登录的 actuator 端点；`/actuator/prometheus` 与 `/actuator/metrics` 都走 JWT 认证（见 `SecurityConfig`）。
> 抓取端如何拿凭证见 §5。

---

## 2. 指标字典

### 2.1 本迭代新增（`TravelMetrics`）

计数器与 Gauge 并存，tag 只有枚举值——**每个 tag 组合的基数是固定的**，禁止把 `userId` / `runId` / `tripId` / `query` 加进来（那会让序列数随用户量线性爆炸）。用户/运行维度的排障走日志与 trace（`traceId` / `runId`），不进指标。

| Micrometer 名 | Prometheus 名 | 类型 | tag | 含义 | 基数 | 埋点 |
| --- | --- | --- | --- | --- | --- | --- |
| `travel.plan.outcome` | `travel_plan_outcome_total` | counter | `result=success\|degraded\|failed` | 规划结果：`success` 正常产出方案；`degraded` 方案产出了但本轮跳过了研究/结构化规划（预算不足）；`failed` 规划抛异常 | 3 | `AgentEngine#doHandle` 规划段 |
| `travel.confirm.outcome` | `travel_confirm_outcome_total` | counter | `result=success\|conflict\|failed` | 确认提交结果：`success` 已落库；`conflict` 并发抢占失败/内容指纹不符/行程版本已变（可重试）；`failed` 方案不存在或过期/风险门拒绝/提交抛异常 | 3 | `PlanConfirmationService#confirm` |
| `travel.rag.retrieval` | `travel_rag_retrieval_total` | counter | `result=hit\|miss` | RAG 检索是否返回非空结果（`hit`=有候选，`miss`=空；低分拒答仍算 `hit`） | 2 | `RagService#searchWithCitations` |
| `travel.llm.tokens` | `travel_llm_tokens_total` | counter | `type=prompt\|completion` | LLM 响应 `usage` 里的 token 数，累加值（缺失 usage 时不记，不估算） | 2 | `OpenAiCompatibleLlmClient#recordUsage` |
| `travel.llm.cost.usd` | `travel_llm_cost_usd_total` | counter | 无 | LLM 成本估算（美元累加）。单价未配置（默认 0）时**不创建该指标**，避免虚报 0 成本 | 1 | `TravelMetrics#recordLlmUsage` |
| `travel.agent.spend` | `travel_agent_spend_total` | counter | `result=within\|exhausted`、`limit=none\|llm_calls\|tool_calls\|retries\|tokens\|cost` | 每轮 Agent 调用的花费预算结果：`within`=没触顶；`exhausted`=某一项到顶并按降级语义收尾，`limit` 指出卡在哪一项 | 2×6=12（上限 12 条序列） | `AgentEngine#recordSpend`（经 `TravelMetrics#spendOutcome`） |
| `outbox.status` | `outbox_status` | **gauge** | `status=pending\|dispatching\|done\|dead` | `plan_outbox` 按状态的行数；每次抓取现查库（投递器停摆时积压仍可见）。DB 查询失败时采样为 NaN，**不伪造成 0**（NaN 不触发 `>0` 告警） | 4 | `OutboxStatusMetrics`（构造时注册） |
| `agent.run.abandoned` | `agent_run_abandoned_total` | counter | 无 | 被恢复任务判为 ABANDONED 的运行数（结果未知；与执行体自报 FAILED 区分） | 1 | `AgentRunRecoveryJob#recoverOnce` |
| `pending.plan.swept` | `pending_plan_swept_total` | counter | 无 | 过期确认租约被清扫回 PENDING 的次数（持续非 0 = 确认链路在崩溃/超时） | 1 | `PendingPlanRecoveryJob#sweep` |
| `mcp.server.enabled` | `mcp_server_enabled` | **gauge** | 无 | MCP 总开关：1=`travel.mcp.enabled=true`，0=false。配置期已知，是唯一「0 就是真实答案」的 MCP 指标 | 1 | `McpGovernanceMetrics`（构造时注册） |
| `mcp.server.connected` | `mcp_server_connected` | **gauge** | `server` | 该 server 是否接入成功：1=已接入并注册工具，0=连接/握手失败（原因在 `/api/system/status` 的 `mcpLastError`） | 每配置 server 1 条 | 同上 |
| `mcp.server.tools` | `mcp_server_tools` | **gauge** | `server` | 该 server 注册进 ToolRegistry 的工具数（按 `allow-tools` 过滤后） | 每配置 server 1 条 | 同上 |
| `mcp.server.calls` | `mcp_server_calls` | **gauge** | `server` | 被放行并执行的累计调用数（失败/超时的尝试也计入，它们同样烧上游配额）；判读速率用 `rate()` | 每配置 server 1 条 | 同上 |
| `mcp.server.failures` | `mcp_server_failures` | **gauge** | `server` | 放行后的累计执行失败数（**不含**被闸门拒绝的调用） | 每配置 server 1 条 | 同上 |
| `mcp.server.rejected` | `mcp_server_rejected` | **gauge** | `server` | 被治理闸门拒绝的累计调用数（白名单 / 每分钟配额 / 熔断） | 每配置 server 1 条 | 同上 |
| `mcp.server.circuit` | `mcp_server_circuit` | **gauge** | `server`、`state=closed\|open\|half_open` | 该 server 当前熔断状态：命中为 1、其余为 0；不可观测时三态同时 NaN | 每配置 server 3 条 | 同上 |
| `mcp.server.calls.this.minute` | `mcp_server_calls_this_minute` | **gauge** | `server` | 闸门**正在生效**的 60 秒窗口内已用调用数；配额未配置时为 NaN（不是 0 次） | 每配置 server 1 条 | 同上 |
| `mcp.server.quota` | `mcp_server_quota` | **gauge** | `server` | 该 server 的 `max-calls-per-minute` 上限；未配置时为 NaN | 每配置 server 1 条 | 同上 |

口径细节（都是会被问到的）：

- **`degraded` 不等于失败**：它是「用户拿到方案，但这份方案没经过研究/结构化规划」。判断依据是本次规划段内新增的 `AgentContext#recordBudgetDegradation` 数量，而不是「上下文里曾经有过降级」——否则一次预算紧张的会话会在 intent 与 plan 各记一次。
- **空操作方案计 `success`**：「无需改动」是正常业务结论，不是规划失败。
- **RAG `miss` 有两个含义**：知识库确实没资料，或索引没建起来/embedding 坏了。单看 miss 分不清，必须和 `travel.rag.retrieval` 的绝对速率 + 应用日志一起看。
- **token 在内容校验之前记账**：回复为空/被截断的调用，供应商同样计费，漏记会低估成本。
- **成本默认不记**：不同供应商价目表差异极大，写死一个"看起来合理"的默认值等于长期虚报。需要成本 SLO 时按当期价目表配置 `travel.llm.prompt-price-per-1k-usd` / `travel.llm.completion-price-per-1k-usd`（见 `LlmProperties`，默认值均为 `0.0`）。
- **花费预算只记结论，不把用量放进 tag**：`travel.agent.spend` 每轮 +1，`limit` 是枚举（6 个取值）；
  实际用了多少 token/多少钱由 `travel.llm.tokens` 与 `travel.llm.cost.usd` 解释。把 runId/用量写进 tag 是基数炸弹，
  也是本项目指标纪律明令禁止的。
- **`exhausted` 不是失败**：它表示「本轮按上限少做了事，并在回复里如实说明」。`exhausted{limit=...}` 的速率
  才是判断「上限是否设得太紧」的依据：如果 `limit="tokens"` 持续出现，先看是否正常长行程，再决定放宽。
- **每轮花费有台账（V29）**：指标只回答「触没触顶、卡在哪一项」，要回答「这一次 run 花了多少」查
  `agent_run_spend`（`GET /api/agent/runs/{runId}` 的 `spend` 字段同源）：
  `SELECT llm_calls, tool_calls, retries, prompt_tokens, completion_tokens, cost_usd, exhausted
   FROM agent_run_spend WHERE run_id = '…';`。成本列只在配置真实单价时非 0，口径与
  `travel.llm.cost.usd` 一致；表随 Agent 追踪保留期（14 天）清理。
- **MCP 治理计数为什么是 Gauge 而不是 Counter**：calls/failures/rejected 的唯一权威是
  `McpGovernor`（一把锁同时维护计数与熔断状态机）。在别处再累加一个 Micrometer Counter 会立刻出现两份
  会漂移的真相（异常路径漏记、重试路径重复记），而告警与 `/api/system/status` 必须读同一个数。
  Gauge 每次抓取现读闸门快照，数值仍是单调递增的：Prometheus 侧照常写 `rate()` / `increase()`，
  进程重启归零会被按 counter 重置处理（`# TYPE` 标成 gauge 不影响这两个函数）。
- **MCP 的 NaN 纪律**：MCP 未启用（`travel.mcp.enabled=false`）、桥接还没启动（`McpToolBridge` 是异步
  `SmartLifecycle`）、server 不在当前快照里时，逐 server 的序列一律报 **NaN**，绝不用 0 冒充「没调用/没失败」；
  熔断三态此时**同时** NaN（读不到状态 ≠ closed）。真实抓取文本长这样：
  `mcp_server_calls{server="wiki"} NaN`、`mcp_server_circuit{server="wiki",state="open"} NaN`。
  NaN 不会命中 `> 0` / `== 1` 类告警，所以「查不到」既不会被当成故障，也不会被当成健康。
  例外只有 `mcp_server_enabled`：开关在配置期已知，0 是真实答案。
- **`server` tag 来自配置**：`travel.mcp.servers[].name`（空名回落 `unnamed`，与状态接口的显示名同口径）。
  不用运行时发现——桥接异步启动，启动瞬间还没有 server 列表，等它就等于「第一次抓取前没有指标」。
  基数受配置约束（每个 server 7 条 + 熔断 3 条），不随流量增长。
- **熔断态是「调用驱动」的**：冷却结束后的**第一次调用**才会把状态推进到 `half_open` 并放行一次探测，
  成功即 `closed`、失败立刻重新 `open`。所以 `state="open"` 持续不代表上游一直在被探测——也可能是
  「闸门开着但没人调用」，Runbook §20 用 `mcp_server_rejected` 的增速区分这两种情况。
- **`mcp_server_connected` / `mcp_server_tools` 是「启动期接入手势」的结果**：`McpToolBridge` 只在
  `start()` 时建连、`stop()` 时改状态，运行期不重连也不重数工具，所以连接中途挂掉时这两条序列仍是
  `1` / 原值。运行期是否还能用，看 `mcp_server_failures` 的增速、`mcp_server_circuit` 与
  `mcp_server_rejected`——不要拿 `connected=1` 当作「现在健康」。
- **配额压力**：`mcp_server_calls_this_minute`（闸门当前 60 秒窗口的已用量）与 `mcp_server_quota`
  （`max-calls-per-minute` 上限）成对出现，两者相除就是余量；配额未配置时**两条都是 NaN**，不是 0——
  「没设配额」和「本分钟没调用」必须能区分（`McpGovernor` 在未配置配额时让 `callsThisMinute` 恒为 0，
  所以 `McpServerStatus` 用 null 表达「没有这个事实」）。告警见 Runbook §22。

### 2.2 既有指标（不要重复造轮子）

这些指标在本迭代之前已经存在，T5 只消费、不改名、不重造。PromQL 里统一按下面的 Prometheus 名书写。

| Micrometer 名 | Prometheus 名 | 类型 | tag | 含义 | 代码位置 |
| --- | --- | --- | --- | --- | --- |
| `agent.stage` | `agent_stage_seconds_*` | timer | `stage=intent\|plan\|tool\|reflect\|commit` | Agent 各阶段耗时（`reflect` 覆盖并行预览+审核+重规划整段） | `AgentStageTimer` |
| `llm.calls` | `llm_calls_total` | counter | `result=ok\|failed\|skipped\|quota` | LLM 调用结果；`skipped`=未配置 Key，`quota`=超每日配额 | `DelegatingLlmClient` / `OpenAiCompatibleLlmClient` |
| `llm.latency` | `llm_latency_seconds_*` | timer | 无 | 单次真实 LLM 调用耗时（含失败与超时） | `OpenAiCompatibleLlmClient#doChat` |
| `structured.planning` | `structured_planning_total` | counter | `path`、`result` | 结构化规划的路径与结果（规则/结构化、成功/降级） | `StructuredPlanner` |
| `agent.tool.loop` | `agent_tool_loop_total` | counter | `result=completed\|budget_exhausted\|llm_unavailable\|error` | 自主工具循环的结束原因 | `AgentToolLoop#count` |
| `sse.disconnects` | `sse_disconnects_total` | counter | `reason=complete\|timeout\|error\|cancelled` | SSE 断连：`complete`=引擎收尾前连接结束（用户切页/断网）、`timeout`、`error`、`cancelled`=引擎在阶段边界发现已取消 | `AgentController.StreamLifecycle`、`AgentEngine#handle` |
| `ratelimit.failopen` | `ratelimit_failopen_total` | counter | 无 | Redis 限流不可用而放行的次数（每次放行都 WARN 留痕） | `RedisRateCounter#failOpen` |
| `external.degraded` | `external_degraded_total` | counter | `provider`、`reason` | **外部依赖降级**（T3 韧性任务实现，本迭代只消费） | T3 韧性任务（本迭代只消费） |

> `sse.disconnects` 的 `complete` 与 `cancelled` 可能是**同一次断连的两条记录**（控制器按连接记一次，引擎在 finally 又按运行记一次）。做 SLO 时按 reason 分开算（见 §3），不要把四个 reason 相加当"断连总数"。

### 2.3 标准 HTTP 指标（Spring Boot 自带）

| Prometheus 名 | 关键 tag | 说明 |
| --- | --- | --- |
| `http_server_requests_seconds_count` | `uri`、`status`、`method`、`outcome`、`exception` | 请求计数（分母、错误率） |
| `http_server_requests_seconds_sum` | 同上 | 累计耗时（平均值 `sum/count`） |
| `http_server_requests_seconds_bucket` | 同上 + `le` | **需要开启 `percentiles-histogram`**，否则 P95 算不出来（`histogram_quantile` 返回空，告警静默失效） |

### 2.4 可靠执行链路（P0）：数据库状态与恢复任务

P0 的可靠性信号目前主要落在**数据库与日志**，不是 Micrometer 指标。排障时按下面的表查，
不要等一个不存在的指标：

| 信号 | 位置 | 含义 / 查询 |
| --- | --- | --- |
| `agent_run.status=ABANDONED` | `agent_run` | 心跳超时或进程重启，执行体已不存在、结果未知；与执行体自报的 `FAILED` 区分 |
| `agent_run.stage` / `heartbeat_at` | `agent_run` | 当前阶段快照与最近推进时间；恢复任务以 `heartbeat_at`（为空用 `started_at`）判断是否陈旧 |
| 阶段历史 | `agent_run_stage`（`run_id, seq_no`） | 这个 run 依次经过了哪些阶段；与 `agent_run_event` 的 `seq_no` 是两套独立序列，别混用 |
| Outbox 积压 / 失败 | `plan_outbox` | `status=PENDING` 且 `next_attempt_at<=NOW()` 为待投递；`DISPATCHING` 超过 `claimed_at+10min` 为疑似崩溃；`DEAD` 需人工介入（`last_error` 是证据） |
| 事件持久化 | `agent_run_event` | 每 run 的 `seq_no` 应连续；缺号说明落库失败（只记 DEBUG）或运行未创建成功 |
| 每轮花费 | `agent_run_spend`（`run_id` 唯一） | run 收尾写一条快照：`llm_calls` / `tool_calls` / `retries` / `prompt_tokens` / `completion_tokens` / `cost_usd` / `exhausted`；`exhausted` 非空说明本轮某道上限触顶 |
| 租约清扫 / 恢复日志 | 应用日志 | `确认租约清扫：N 条…`、`Outbox 租约恢复：N 条重新排队…`、`Outbox 重试耗尽，置 DEAD…`、`回收心跳超时的 Agent 运行: count=…`；日志只带数量/ID，不带方案内容 |
| 续传结果 | 应用日志 + 客户端 | `resume_timeout` 只发事件、不写库；服务端异常只记 DEBUG 并结束本连接，不影响原始运行 |

可运维调整的配置项（默认值即安全值）：

| 配置键 | 默认 | 作用 |
| --- | --- | --- |
| `travel.pending-plan.lease-minutes` | `5` | 确认租约时长；必须大于一次正常提交耗时 |
| `travel.pending-plan.sweep-interval-ms` | `30000` | 过期 `CONFIRMING` 清扫间隔（启动时另跑一次） |
| `travel.outbox.dispatch-interval-ms` | `5000` | Outbox 投递轮次间隔（fixedDelay） |
| `travel.outbox.batch-size` | `20` | 每轮最多投递条数 |
| `travel.outbox.dispatching-lease-minutes` | `10` | `DISPATCHING` 领取租约，超时视为执行者崩溃 |
| `travel.outbox.max-attempts` | `8` | 失败上限，达到后置 `DEAD` |
| `travel.outbox.base-backoff-seconds` / `max-backoff-seconds` | `10` / `3600` | 指数退避：第 n 次失败等待 `base × 2^(n-1)`，封顶 1h |
| `travel.agent.run-recovery.stale-after-ms` | `600000` | 心跳超时阈值（默认 10 分钟） |
| `travel.agent.run-recovery.interval-ms` / `initial-delay-ms` | `300000` / `300000` | 恢复任务固定延迟与首次延迟（启动时另跑一次） |

前 8 个在 `application.yml` 有环境变量占位（`PENDING_PLAN_*` / `OUTBOX_*`，见 [部署](deployment.md) §5.3.2）；
`travel.agent.run-recovery.*` 只在 `@Value` 里读，**没有对应的 `AGENT_RUN_RECOVERY_*` 环境变量占位**，
要用配置文件或 `SPRING_APPLICATION_JSON` 覆盖。

**三个可靠性指标已落地并接入 [alerts.yml](../operations/alerts.yml) §8（2026-11-25）**：

| 指标 | PromQL 示例 | 告警 | 判读 |
| --- | --- | --- | --- |
| `outbox_status{status="dead"}` | `max(outbox_status{status="dead"}) > 0`（持续 10m） | `TravelOutboxDeadPresent`（critical） | 确认后的同步工作已死，必须人工补投；先看该行 `last_error`/`attempts` |
| `outbox_status{status="pending"}` | `max(outbox_status{status="pending"}) > 100`（持续 15m） | `TravelOutboxBacklogHigh`（warning） | 投递器停摆或下游持续失败；积压期间用户会「已确认但未同步」 |
| `agent_run_abandoned_total` | `increase(agent_run_abandoned_total[30m]) > 0` | `TravelAgentRunAbandoned`（warning） | 进程崩溃/重启/OOM；结果未知，提示用户重试 |
| `pending_plan_swept_total` | `increase(pending_plan_swept_total[30m]) > 5` | `TravelPendingPlanSweptHigh`（warning） | 确认提交超过租约；查 DB 延迟与提交耗时，别只调大租约 |

口径：`outbox_status` 是抓取时现查的 Gauge（投递器不跑也不会冻结成「0 积压」）；DB 不可用时时序为 NaN，
**不伪造成 0**（`> 0` 告警不会误报）。`dead` 与 `pending` 都用 `max()` 而不是 `sum()`：多实例时每个实例都查同一张表，
`max` 避免把同一份积压乘以实例数。**不要把 SQL 轮询塞进抓取路径之外的地方。**

---

## 3. SLO 表

窗口与阈值是**可运营的初值**，不是从真实流量拟合出来的：上线第一周用 §8 的验证命令回填基线，再调紧。所有分母都套 `clamp_min(..., 1e-9)`：没有流量时 `0/0` 会产出 NaN，把 NaN 写进告警表达式等于给告警装了个静音开关。

| SLO | 目标 | 窗口 | SLI / PromQL | 告警（见 [alerts.yml](../operations/alerts.yml)） | 处置 |
| --- | --- | --- | --- | --- | --- |
| **规划可用率**（用户拿到方案的比例，`degraded` 也算可用） | ≥ 99.5% | 30m 滚动 / 28d 统计 | `sum(rate(travel_plan_outcome_total{result=~"success\|degraded"}[30m])) / clamp_min(sum(rate(travel_plan_outcome_total[30m])), 1e-9)` | `TravelPlanOutcomeFailed`、`TravelPlanAvailabilityLow` | [runbook §1](../operations/runbook.md#1-travelplanavailabilitylow-p0) |
| **规划健康率**（未降级的比例） | ≥ 95% | 30m | `sum(rate(travel_plan_outcome_total{result="success"}[30m])) / clamp_min(sum(rate(travel_plan_outcome_total[30m])), 1e-9)` | `TravelPlanDegradedHigh`（P1） | [runbook §2](../operations/runbook.md#2-travelplandegradedhigh-p1) |
| **确认提交成功率** | ≥ 99.5% | 10m | `sum(rate(travel_confirm_outcome_total{result="success"}[10m])) / clamp_min(sum(rate(travel_confirm_outcome_total{result=~"success\|failed"}[10m])), 1e-9)`（`conflict` 是并发/版本竞争，单列，不算失败） | `TravelConfirmSuccessRateLow` | [runbook §3](../operations/runbook.md#3-travelconfirmsuccessratelow-p0) |
| **确认冲突率** | ≤ 2% | 30m | `sum(rate(travel_confirm_outcome_total{result="conflict"}[30m])) / clamp_min(sum(rate(travel_confirm_outcome_total[30m])), 1e-9)` | `TravelConfirmConflictHigh`（P2） | [runbook §4](../operations/runbook.md#4-travelconfirmconflicthigh-p2) |
| **SSE 异常中断率**（传输层错误/超时） | < 1% | 10m | `sum(rate(sse_disconnects_total{reason=~"error\|timeout"}[10m])) / clamp_min(sum(rate(http_server_requests_seconds_count{uri="/api/agent/stream"}[10m])), 1e-9)` | `TravelSseAbnormalDisconnectHigh` | [runbook §5](../operations/runbook.md#5-travelsseabnormaldisconnecthigh-p0) |
| **SSE 客户端中断率**（用户切页/断网，非故障） | < 10% | 30m | `sum(rate(sse_disconnects_total{reason="complete"}[30m])) / clamp_min(sum(rate(http_server_requests_seconds_count{uri="/api/agent/stream"}[30m])), 1e-9)` | `TravelSseClientAbortHigh`（P1） | [runbook §6](../operations/runbook.md#6-travelsseclientaborthigh-p1) |
| **外部依赖降级率** | < 5% | 10m | `sum(rate(external_degraded_total[10m])) / clamp_min(sum(rate(http_server_requests_seconds_count[10m])), 1e-9)` | `TravelExternalDegradedHigh`（P1）、`TravelExternalProviderDegraded`（P1） | [runbook §7](../operations/runbook.md#7-travelexternaldegradedhigh-p1) |
| **HTTP P95**（排除 SSE 长连接） | < 800ms | 5m | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/api/agent/stream"}[5m])))` | `TravelHttpLatencyP95High`（P1） | [runbook §8](../operations/runbook.md#8-travelhttplatencyp95high-p1) |
| **规划阶段 P95** | < 25s（总预算 30s 的 80%） | 5m | `histogram_quantile(0.95, sum by (le) (rate(agent_stage_seconds_bucket{stage="plan"}[5m])))` | `TravelPlanStageLatencyP95High`（P1） | [runbook §9](../operations/runbook.md#9-travelplanstagelatencyp95high-p1) |
| **LLM 失败率** | < 10% | 10m | `sum(rate(llm_calls_total{result="failed"}[10m])) / clamp_min(sum(rate(llm_calls_total{result=~"ok\|failed"}[10m])), 1e-9)` | `TravelLlmFailureHigh` | [runbook §10](../operations/runbook.md#10-travelllmfailurehigh-p0) |
| **LLM 配额拒绝率** | < 5% | 30m | `sum(rate(llm_calls_total{result="quota"}[30m])) / clamp_min(sum(rate(llm_calls_total[30m])), 1e-9)` | `TravelLlmQuotaRejectedHigh`（P2） | [runbook §11](../operations/runbook.md#11-travelllmquotarejectedhigh-p2) |
| **LLM 成本速率** | < 2 USD/h（按实例） | 1h | `sum(rate(travel_llm_cost_usd_total[1h])) * 3600` | `TravelLlmCostBudgetHigh`（P1） | [runbook §12](../operations/runbook.md#12-travelllmcostbudgethigh-p1) |
| **RAG 命中率** | ≥ 30% | 30m | `sum(rate(travel_rag_retrieval_total{result="hit"}[30m])) / clamp_min(sum(rate(travel_rag_retrieval_total[30m])), 1e-9)` | `TravelRagHitRateLow`（P1） | [runbook §13](../operations/runbook.md#13-travelraghitratelow-p1) |

> **为什么 SSE 要分两个率**：`complete` 是用户在流结束前关页面（产品行为，高了说明前端/用户习惯问题），`error|timeout` 是服务端/网络故障（要人处理）。混在一起算会让"用户切页"淹没真正的故障。
> **为什么 P95 排除 `/api/agent/stream`**：SSE 请求的"耗时"等于整个会话时长（分钟级），混进 HTTP P95 后看板上永远是超时。

---

## 4. 告警与阈值

- 规则文件：[`docs/operations/alerts.yml`](../operations/alerts.yml)（PrometheusRule，`kubectl apply` 或写进 Prometheus `rule_files`）。
  注意它是 **PrometheusRule CRD**（顶层 `apiVersion/kind/spec`），裸 Prometheus 的 `rule_files` 只认 `groups:`，
  用 `node scripts/alerts-rules-extract.mjs -Out alerts-rules.yml` 抽出后再交给 Prometheus（当前 25 条 / 10 组：
  既有 22 条在 2026-11-25 用 `promtool check rules` 逐条验过；2026-11-26 新增 MCP 治理组 3 条后，
  用仓库内 `.tools/prometheus/prometheus-3.16.0.windows-amd64/promtool.exe check rules` 复核：`SUCCESS: 25 rules found`）。
- 路由与送达：[`docs/operations/alert-routing.md`](../operations/alert-routing.md) + `alertmanager.yml`
  （severity 路由、抑制、兜底）；`scripts/alert-routing-drill.ps1` 一条命令验证「规则 firing → 路由 → 送达 → resolved」。
- 分级：`severity=critical`（P0，立即处置，用户已受影响）、`warning`（P1，当天处置）、`info`（P2，趋势问题，周会看）。
- 每条规则都带 `for`（防抖，避免一次抖动就叫人）、`summary`、`description`、`runbook_url`。
- **`for` 的取值原则**：用户可见故障 5–10m，趋势类 30–60m。窗口短于抓取间隔（常见 15–30s）的告警没有意义。

| 告警 | 严重度 | for | 触发条件（摘要） |
| --- | --- | --- | --- |
| `TravelAgentServerDown` | critical | 3m | `up == 0`（抓取目标掉线） |
| `TravelPlanOutcomeFailed` | critical | 10m | 规划 `failed` 速率 > 0.05/s |
| `TravelPlanAvailabilityLow` | critical | 15m | 规划可用率 < 99.5% |
| `TravelPlanDegradedHigh` | warning | 30m | 规划 `degraded` 占比 > 5% |
| `TravelConfirmSuccessRateLow` | critical | 10m | 确认成功率 < 99.5%（样本 ≥ 5） |
| `TravelConfirmConflictHigh` | info | 30m | 确认冲突率 > 2% |
| `TravelSseAbnormalDisconnectHigh` | critical | 10m | SSE `error\|timeout` / 流请求数 > 1% |
| `TravelSseClientAbortHigh` | warning | 30m | SSE `complete` / 流请求数 > 10% |
| `TravelLowHistogramMissing` | warning | 15m | `absent(http_server_requests_seconds_bucket)`（只兜 HTTP；`agent.stage` / `llm.latency` 的 histogram 缺失不在规则覆盖内，巡检时人工确认） |
| `TravelExternalDegradedHigh` | warning | 10m | 外部降级 / 请求数 > 5% |
| `TravelExternalProviderDegraded` | warning | 15m | 单 provider 每秒降级 > 0.2 |
| `TravelHttpLatencyP95High` | warning | 10m | HTTP P95（排除 SSE）> 800ms |
| `TravelPlanStageLatencyP95High` | warning | 10m | 规划阶段 P95 > 25s |
| `TravelLlmFailureHigh` | critical | 10m | LLM 失败率 > 10%（服务仍可用，但已全面降级到规则路径） |
| `TravelLlmQuotaRejectedHigh` | info | 30m | LLM 配额拒绝率 > 5% |
| `TravelLlmCostBudgetHigh` | warning | 1h | 成本 > 2 USD/h |
| `TravelRagHitRateLow` | warning | 30m | RAG 命中率 < 30%（样本 ≥ 10） |
| `TravelRateLimitFailOpen` | warning | 15m | `ratelimit_failopen_total` 持续增长（Redis 限流失效） |
| `TravelMcpCircuitOpen` | warning | 5m | `max by (server, instance) (mcp_server_circuit{state="open"}) == 1`（某 server 已被治理闸门摘掉，模型拿不到它的工具） |
| `TravelMcpServerFailureRateHigh` | warning | 10m | 同一实例上 MCP server 失败率 > 30%（10m 内至少 5 次调用，小样本保护） |

> **单 provider 降级告警的实际延迟**：`TravelExternalProviderDegraded` 用 `rate(external_degraded_total[15m]) > 0.2` 且 `for: 15m`，从降级开始到 firing 实测约 **16 分钟**（2026-11-24 实跑，见[演练快照](../review/2026-11-24-amap-degradation-alert-drill.md)）。它表达的是「已持续降级」，不要当早期预警用；要更早发现需要另加短窗口规则（当前未加，阈值与 `for` 未改）。

---

## 5. 抓取配置

### 5.1 前置：Spring 侧配置（`application.yml` 归 T1 owner）

`micrometer-registry-prometheus` 已在 `pom.xml`（runtime scope）。端点暴露由 T1 完成，**当前已生效**：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus   # ✅ 已合并（/actuator/prometheus 可访问，需登录）
```

**仍需合并的 P95 历史桶配置**（T5 不直接改 `application.yml`，以下片段交给根会话合并；没有它 §3 的 P95 告警会静默失效）：

```yaml
management:
  metrics:
    distribution:
      # P95 的前提：没有 histogram 就没有 _bucket，histogram_quantile 返回空、
      # 依赖它的告警会**永远不触发**（比误报更危险）
      percentiles-histogram:
        http.server.requests: true
        agent.stage: true
        llm.latency: true
```

说明：
- 只开 histogram 不改变既有指标名，只是在 `*_seconds_count/sum` 之外多出 `*_seconds_bucket{le=...}`。
- 已用启动参数 `--management.metrics.distribution.percentiles-histogram.*=true` 实测：`agent_stage_seconds_bucket{stage="plan",le="30.0"}` 与 `http_server_requests_seconds_bucket` 均正常导出（见 §10 验证记录）。
- `travel.llm.*` 成本单价可选：`travel.llm.prompt-price-per-1k-usd` / `travel.llm.completion-price-per-1k-usd`（默认 `0` = 不记成本；env 形式如 `TRAVEL_LLM_PROMPTPRICEPER1KUSD`，或直接用 `SPRING_APPLICATION_JSON`）。

### 5.2 认证：`/actuator/prometheus` 需要登录

当前安全策略下**只有 `/actuator/health` 免登录**，其余 actuator 端点都要 JWT。抓取端有两种落地方式：

**方式 A（推荐，需安全评审）**：给 actuator 单独管理端口并只在内网暴露

```yaml
management:
  server:
    port: 8081      # 仅容器/内网可达，不经公网反代
    address: 127.0.0.1
```

同时在 `SecurityConfig` 里对管理端口的 `/actuator/**` 放行（或按来源 IP 限制）。这是**安全策略变更**，由安全配置 owner 决定，不由监控侧私自放开。

**方式 B（不改代码，临时可用）**：Prometheus 带 JWT 抓取

监控账号登录拿到 token 后写入文件，Prometheus 用 `authorization.credentials_file` 读取；token 过期前由定时任务刷新。缺点是 token 过期会静默断抓——必须配 `up == 0` 告警（`TravelAgentServerDown` 已覆盖）。

```yaml
# prometheus.yml
scrape_configs:
  - job_name: travel-agent-server
    scrape_interval: 30s
    scrape_timeout: 10s
    metrics_path: /actuator/prometheus
    scheme: http
    static_configs:
      - targets: ["travel-agent-server:8080"]
        labels:
          service: travel-agent-server
          env: prod
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/travel-agent.jwt
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance

  - job_name: travel-agent-server-health   # 探活不需要凭证
    metrics_path: /actuator/health
    static_configs:
      - targets: ["travel-agent-server:8080"]
```

### 5.3 规则挂载

```yaml
# prometheus.yml
rule_files:
  - /etc/prometheus/rules/travel-agent-alerts.yml
```

```bash
# 语法自检：alerts.yml 是 PrometheusRule CRD，promtool 只认顶层 groups:，必须先抽出 spec.groups
node scripts/alerts-rules-extract.mjs -Out /tmp/alerts-rules.yml
promtool check rules /tmp/alerts-rules.yml
```

没有 `promtool` 时至少用 YAML 解析校验（见 §8）；本仓库 `.tools/prometheus/prometheus-3.16.0.windows-amd64/promtool.exe` 就是可用的 promtool，别直接拿它校验 CRD 原文（会因缺少顶层 groups: 报错）。

---

## 6. Dashboard

可导入文件：[`docs/operations/grafana-travel-agent.json`](../operations/grafana-travel-agent.json)。

- 导入：Grafana → Dashboards → Import → 上传 JSON → 选择 Prometheus 数据源（文件里用 `DS_PROMETHEUS` 占位）。
- 面板分组：**概览**（规划/确认成功率、QPS、错误率）、**Agent 链路**（阶段 P95、工具循环结果、结构化规划）、**外部依赖与 SSE**（降级、断连）、**RAG 与 LLM 成本**。
- 每个面板的 PromQL 与 §3 的 SLI 一致，改 SLO 时两边一起改。
- 面板单位：成功率 `percentunit`（0–1）、耗时 `s`、成本 `currencyUSD`、token `short`。

---

## 7. 日志字段约定

指标回答"有多少"，日志回答"是哪一次、哪个人、哪个供应商"。为了能按同一维度聚合，业务与基础设施日志统一带以下字段（结构化日志或 `key=value` 文本均可，Promtail/Loki 按 key 提取）：

| 字段 | 取值/示例 | 说明 | 脱敏 |
| --- | --- | --- | --- |
| `traceId` | `3f2a9c...` | 单次 HTTP 请求/一次 Agent 运行的追踪 id（既有 trace） | 否 |
| `userIdHash` | `u_8f14e45f` | 用户 id 的稳定哈希（前 8–16 位）。**禁止打印明文 userId 与用户名** | 是（不可逆） |
| `runId` | `run_20261119_abc` | Agent 运行 id（`AgentTraceService.startRun` 产出），跨规划→审核→提交串联 | 否 |
| `tripId` | `1234` | 行程 id（涉及行程的日志） | 否 |
| `provider` | `amap` / `weather` / `llm` / `redis` | 外部依赖名，与 `external.degraded{provider}` 的 tag 对齐 | 否 |
| `degraded` | `true/false` | 本次调用/本阶段是否发生降级（与 `external.degraded`、`budget_degraded` 事件对齐） | 否 |

示例（一次降级规划）：

```
2026-11-19T14:20:31+08:00 WARN  c.t.a.agent.PlannerAgent - provider=amap degraded=true reason=timeout
    traceId=3f2a9c runId=run_20261119_abc tripId=1234 userIdHash=u_8f14e45f msg="高德超时，改用估算坐标"
```

约定说明：
- **`userIdHash` 不是明文**：日志检索需要"同一个用户的所有请求"时用哈希做 join，需要定位具体用户时走受控的审计通道（数据库 + 权限审批），不在日志里放明文。
- **`provider` 取值必须稳定**：它同时是 `external.degraded{provider}` 的 tag，乱写会让降级率被拆成多个低基数序列。
- **`degraded` 只在真的降级时为 `true`**：正常路径不要打这个字段（默认 false），否则日志量翻倍且无法 grep。
- **不要记录 prompt/response 原文**：其中可能含用户隐私与 Key 片段；排障需要时用 `runId` 回查受控的 trace 表。

---

## 8. 验证命令

```bash
# 1) 指标单测（22 个：指标 key/tag、失败路径、故障隔离、usage 解析）
cd travel-agent-server
mvn -o -q -Dtest='TravelMetricsTest,PlanConfirmationMetricsTest,RagServiceMetricsTest,LlmUsageMetricsTest' test

# 2) JSON / YAML 语法
cd ..   # 仓库根
python -m json.tool docs/operations/grafana-travel-agent.json > /dev/null && echo "dashboard json ok"
python -c "import yaml,sys; yaml.safe_load(open('docs/operations/alerts.yml',encoding='utf-8')); print('alerts yaml ok')"

# 3) 抓取验证（应用已启动且 §5.1 配置已合并）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/actuator/prometheus | grep -c '^travel_'   # 期望 > 0
```

排障顺序：端点 404 → `management.endpoints.web.exposure.include` 没加 `prometheus`；端点 401 → 抓取凭证没带；`travel_*` 一个都没有 → 应用还没发生过对应业务（指标是懒创建的，首次埋点后才出现）；`*_bucket` 缺失 → `percentiles-histogram` 没开。

---

## 9. 已知限制与后续

1. **`external.degraded` 已实跑（2026-11-24）**：`external_degraded_total{provider,reason}` 由高德 / LLM / Embedding / 天气 / Redis / S3 各自失败路径写入（2026-11-27 起发票 OCR 写 `provider=ocr`，默认关闭、缺真实 Key 未实跑）；`TravelExternalProviderDegraded` 与 `TravelExternalDegradedHigh` 已在持续高德降级下真实 firing 并恢复，见[演练快照](../review/2026-11-24-amap-degradation-alert-drill.md)。注意单 provider 那条的首个 firing 延迟实测 ≈16 分钟（`rate[15m]` + `for: 15m`），它是「已持续降级」的告警，不是早期预警。
2. **SLO 阈值是初值**：规划可用率 99.5%、SSE 中断 1%、RAG 命中 30%、成本 2 USD/h 需用第一周真实数据回填。
3. **成本单价默认 0**：不配置就永远没有成本数据；这是刻意选择（不虚报）。
4. **`sse.disconnects` 存在重复计数**：同一次断连可能记 `complete`（控制器）+ `cancelled`（引擎）。SLO 已按 reason 分开，不要求总和。
5. **P95 依赖 histogram**：`percentiles-histogram` 未开启时 P95 告警静默失效，`TravelLowHistogramMissing` 专门兜这个坑——但**只兜 `http.server.requests` 一条**（`absent(http_server_requests_seconds_bucket)`），`agent.stage` / `llm.latency` 缺失不会触发规则，需要巡检时人工确认（2026-11-24 实跑时核对过表达式与规则的差异）。
6. **多实例聚合**：`travel.llm.cost.usd` 按实例累加，成本看板用 `sum by (instance)` 还是全局 `sum` 取决于部署拓扑；告警阈值 2 USD/h 是按单实例写的。
7. **可靠性信号已补指标**：`outbox.status`（Gauge）、`agent.run.abandoned`、`pending.plan.swept` 已落地并接入 4 条规则（§2.4/§4）；MCP 逐 server 治理指标 `mcp.server.*`（含配额窗口两条）于 2026-11-26 落地并接入 3 条规则（§2.1/§4）；仍缺高德 10021 专项（见 [Runbook](../operations/runbook.md) 附录 C）。
8. **事件载荷会被截断**：单条 SSE 事件超过约 1,000,000 字符时写入侧截断并 WARN；续传补发会把截断文本当字符串发回，客户端不能假设所有事件都是完整 JSON。完整方案仍以 `complete` 事件/运行详情为准。
9. **MCP 配额窗口的 0 与 NaN 分得清**：`McpGovernor` 在**未配置** `max-calls-per-minute` 时让 `callsThisMinute` 恒为 0，与「本窗口确实没调用」是同一个 0。因此 `McpServerStatus` 用 `Integer`（null = 没有这个事实）而不是 `int`，指标侧对 null 报 **NaN**：`mcp_server_calls_this_minute` / `mcp_server_quota` 只有在配额已配置时才有值。运维侧判读见 Runbook §22；`/api/system/status` 的 `mcpServers[]` 也如实给 `null`（不落 0）。
---

## 10. 验证记录（2026-11-19）

环境：Windows + MSYS2、JDK 17.0.1、Maven 3.9.16、`travel-agent-server-0.1.0.jar`（含本迭代改动）。

| 验证项 | 命令/方式 | 结果 |
| --- | --- | --- |
| 指标单测 | `mvn -o -q -Dtest='TravelMetricsTest,PlanConfirmationMetricsTest,RagServiceMetricsTest,LlmUsageMetricsTest' test` | **22/22 通过**（TravelMetrics 8、PlanConfirmation 5、LlmUsage 5、RagService 4） |
| 既有时序回归 | `-Dtest='AgentStageMetricsTest,RagServiceThresholdTest,DelegatingLlmClientTest,PlanRepairServiceTest'` | 12/12 通过（含 Spring 上下文装配 `TravelMetrics` bean） |
| HTTP 端点冒烟 | 临时实例 `--server.port=18099` + 独立 H2 文件库（不碰共享 data/） | `/actuator/health` → `{"status":"UP"}`；`/actuator/prometheus` 可抓取（JWT 后 200） |
| 端到端指标 | `POST /api/agent/chat`（上海三天亲子）→ `POST /api/agent/confirm` | 导出 `travel_plan_outcome_total{result="success"} 1.0`、`travel_rag_retrieval_total{result="hit"} 1.0`、`travel_confirm_outcome_total{result="success"} 1.0`；`/actuator/metrics/travel.plan.outcome` 的 `availableTags.result=[success]` |
| LLM token 真实解析 | 同上（实例配置了真实 LLM Key） | `travel_llm_tokens_total{type="prompt"} 3225`、`{type="completion"} 699`（来自响应 `usage`）；`travel_llm_cost_usd_total` **不存在**（单价默认 0，符合"不虚报"设计） |
| P95 桶 | 启动参数开启 histogram | `agent_stage_seconds_bucket{stage="plan",le="30.0"}`、`http_server_requests_seconds_bucket`（345 条）均导出 |
| 告警规则 | `python -c "import yaml; ..."`（promtool 不可用） | 8 组 18 条规则，每条含 `alert/expr/for/labels.severity/annotations(summary,description,runbook_url)` |
| Dashboard | `python -m json.tool docs/operations/grafana-travel-agent.json` | 合法 JSON，14 个面板 |
| Runbook 锚点 | 脚本比对 `alerts.yml`/本文档引用的 `runbook.md#...` 与 `runbook.md` 标题 | 16 个引用锚点全部命中（0 broken） |

> **2026-11-24 补充**：高德降级告警实跑 —— `TravelExternalProviderDegraded{provider="amap"}`（`for: 15m`）与 `TravelExternalDegradedHigh`（`for: 10m`）在持续降级下 pending → firing，停止降级后回落 inactive；`/api/trips/map` 请求数与 `external_degraded_total` 1:1。见[演练快照](../review/2026-11-24-amap-degradation-alert-drill.md)。

> **2026-11-26 补充（MCP 逐 server 治理指标）**：`McpGovernanceMetrics` 新增 7 条 `mcp.server.*` 序列。用真实 `PrometheusMeterRegistry` 抓取确认导出名与 NaN 形态：`mcp_server_calls{server="amap"} 7.0`、`mcp_server_circuit{server="amap",state="open"} 1.0`、不可观测的 server 是 `mcp_server_calls{server="wiki"} NaN`（不是 0）。单测 9 个用例（含 NaN 纪律、三态映射、现读语义）全绿；`mvn -B test -Dtest='McpGovernanceMetricsTest,McpGovernorTest,McpBridgeTest,SystemControllerStatusTest,OutboxStatusMetricsTest'` 共 33 个用例通过；`node scripts/alerts-rules-extract.mjs` 抽出 **10 组 / 24 条**（改动前 9 组 / 22 条）。规则文件用仓库内 `.tools/prometheus/prometheus-3.16.0.windows-amd64/promtool.exe check rules` 校验：改动前 `SUCCESS: 22 rules found`、改动后 `SUCCESS: 24 rules found`。
