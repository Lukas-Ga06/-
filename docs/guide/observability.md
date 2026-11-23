# 可观测性：SLO、指标字典、告警与 Dashboard

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.12 / Micrometer Prometheus registry） | 最后验证：2026-11-19 | 事实来源：`travel-agent-server/src/main/java/com/travel/agent/observability/TravelMetrics.java`、`.../agent/AgentStageTimer.java`、`travel-agent-server/src/main/resources/application.yml`、`docs/operations/`

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

全部为计数器（counter），tag 只有枚举值——**每个 tag 组合的基数是固定的**，禁止把 `userId` / `runId` / `tripId` / `query` 加进来（那会让序列数随用户量线性爆炸）。用户/运行维度的排障走日志与 trace（`traceId` / `runId`），不进指标。

| Micrometer 名 | Prometheus 名 | 类型 | tag | 含义 | 基数 | 埋点 |
| --- | --- | --- | --- | --- | --- | --- |
| `travel.plan.outcome` | `travel_plan_outcome_total` | counter | `result=success\|degraded\|failed` | 规划结果：`success` 正常产出方案；`degraded` 方案产出了但本轮跳过了研究/结构化规划（预算不足）；`failed` 规划抛异常 | 3 | `AgentEngine#doHandle` 规划段 |
| `travel.confirm.outcome` | `travel_confirm_outcome_total` | counter | `result=success\|conflict\|failed` | 确认提交结果：`success` 已落库；`conflict` 并发抢占失败/内容指纹不符/行程版本已变（可重试）；`failed` 方案不存在或过期/风险门拒绝/提交抛异常 | 3 | `PlanConfirmationService#confirm` |
| `travel.rag.retrieval` | `travel_rag_retrieval_total` | counter | `result=hit\|miss` | RAG 检索是否返回非空结果（`hit`=有候选，`miss`=空；低分拒答仍算 `hit`） | 2 | `RagService#searchWithCitations` |
| `travel.llm.tokens` | `travel_llm_tokens_total` | counter | `type=prompt\|completion` | LLM 响应 `usage` 里的 token 数，累加值（缺失 usage 时不记，不估算） | 2 | `OpenAiCompatibleLlmClient#recordUsage` |
| `travel.llm.cost.usd` | `travel_llm_cost_usd_total` | counter | 无 | LLM 成本估算（美元累加）。单价未配置（默认 0）时**不创建该指标**，避免虚报 0 成本 | 1 | `TravelMetrics#recordLlmUsage` |

口径细节（都是会被问到的）：

- **`degraded` 不等于失败**：它是「用户拿到方案，但这份方案没经过研究/结构化规划」。判断依据是本次规划段内新增的 `AgentContext#recordBudgetDegradation` 数量，而不是「上下文里曾经有过降级」——否则一次预算紧张的会话会在 intent 与 plan 各记一次。
- **空操作方案计 `success`**：「无需改动」是正常业务结论，不是规划失败。
- **RAG `miss` 有两个含义**：知识库确实没资料，或索引没建起来/embedding 坏了。单看 miss 分不清，必须和 `travel.rag.retrieval` 的绝对速率 + 应用日志一起看。
- **token 在内容校验之前记账**：回复为空/被截断的调用，供应商同样计费，漏记会低估成本。
- **成本默认不记**：不同供应商价目表差异极大，写死一个"看起来合理"的默认值等于长期虚报。需要成本 SLO 时按当期价目表配置 `travel.llm.prompt-price-per-1k-usd` / `travel.llm.completion-price-per-1k-usd`（见 `LlmProperties`，默认值均为 `0.0`）。

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
| `TravelLowHistogramMissing` | warning | 15m | `absent(agent_stage_seconds_bucket)` 或 `absent(http_server_requests_seconds_bucket)` |
| `TravelExternalDegradedHigh` | warning | 10m | 外部降级 / 请求数 > 5% |
| `TravelExternalProviderDegraded` | warning | 15m | 单 provider 每秒降级 > 0.2 |
| `TravelHttpLatencyP95High` | warning | 10m | HTTP P95（排除 SSE）> 800ms |
| `TravelPlanStageLatencyP95High` | warning | 10m | 规划阶段 P95 > 25s |
| `TravelLlmFailureHigh` | critical | 10m | LLM 失败率 > 10%（服务仍可用，但已全面降级到规则路径） |
| `TravelLlmQuotaRejectedHigh` | info | 30m | LLM 配额拒绝率 > 5% |
| `TravelLlmCostBudgetHigh` | warning | 1h | 成本 > 2 USD/h |
| `TravelRagHitRateLow` | warning | 30m | RAG 命中率 < 30%（样本 ≥ 10） |
| `TravelRateLimitFailOpen` | warning | 15m | `ratelimit_failopen_total` 持续增长（Redis 限流失效） |

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
# 语法自检（有 promtool 时）
promtool check rules docs/operations/alerts.yml
```

`promtool` 不可用时，本仓库用 YAML 解析校验（见 §8）。

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

1. **`external.degraded` 由 T3 交付**：本文档与 Dashboard/告警按 `external_degraded_total{provider,reason}` 约定写好；T3 未合入前相应面板为空、`TravelExternalDegradedHigh` 不会触发（不会误报，但也没有覆盖）。
2. **SLO 阈值是初值**：规划可用率 99.5%、SSE 中断 1%、RAG 命中 30%、成本 2 USD/h 需用第一周真实数据回填。
3. **成本单价默认 0**：不配置就永远没有成本数据；这是刻意选择（不虚报）。
4. **`sse.disconnects` 存在重复计数**：同一次断连可能记 `complete`（控制器）+ `cancelled`（引擎）。SLO 已按 reason 分开，不要求总和。
5. **P95 依赖 histogram**：`percentiles-histogram` 未开启时 P95 告警静默失效，`TravelLowHistogramMissing` 专门兜这个坑。
6. **多实例聚合**：`travel.llm.cost.usd` 按实例累加，成本看板用 `sum by (instance)` 还是全局 `sum` 取决于部署拓扑；告警阈值 2 USD/h 是按单实例写的。
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
