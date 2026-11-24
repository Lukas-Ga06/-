# 故障处理手册（Runbook）：travel-agent 告警处置

- **文档性质**：On-call 处置手册，与 `docs/operations/alerts.yml` 一一对应
- **适用版本**：`travel-agent-server` 0.1.0（Spring Boot 3.5.16，V2.2 HEAD）
- **最后更新**：2026-11-22（新增附录 A/B/C：日志脱敏、限流与配额验证、告警矩阵补充）
- **关联**：[可观测性/SLO](../guide/observability.md) ｜ [告警规则](alerts.yml) ｜ [Dashboard](grafana-travel-agent.json)
- **对应关系**：每个 `## N. AlertName` 就是 `alerts.yml` 里 `runbook_url` 的小节锚点（`#N-alertname-pX`），改标题必须同步改 YAML。

处置原则（比任何单条排查步骤都重要）：

1. **先止损，后定位**。用户受影响时优先恢复（回滚 / 关闭特性开关 / 切配置），根因分析放到恢复之后。
2. **指标是旁路**。所有埋点都包了 try/catch（指标故障不影响主流程），所以"指标没数据"本身不等于业务坏——但也不要把"没数据"当"没故障"。
3. **不要凭告警名猜根因**。每条 P0 的第一动作都是「用原始指标 + 日志确认现象真实存在」，误报在这个系统里是可能的（见 §14 histogram 缺失、§7 T3 指标未合入）。
4. **回滚优先级**：配置回滚 > 镜像/版本回滚 > 数据修复。涉及已落库数据时先备份再动手。

---

## 通用工具箱

```bash
# 0) 基础变量
BASE=${BASE:-http://127.0.0.1:8080}
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)

# 1) 探活
curl -s "$BASE/actuator/health"

# 2) 指标原始值（JSON，最可靠）
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/travel.plan.outcome"
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/travel.confirm.outcome"
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/sse.disconnects"
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/llm.calls"

# 3) 一次规划运行的追踪（runId 从 SSE 的 run 事件或响应头拿）
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/agent/runs/<runId>"
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/agent/runs/<runId>/tasks"

# 4) 日志（按部署方式选一条）
docker logs -f --tail=500 <travel-agent-server 容器名>
journalctl -u travel-agent-server -n 500 -f
tail -f logs/travel-agent-server.log
```

日志检索约定见 [observability.md §7](../guide/observability.md)：按 `traceId` / `runId` / `provider` / `degraded` 过滤，**不要**按明文 userId 搜。

严重度与响应时限：`critical`(P0) 15 分钟内响应、1 小时内恢复或给出明确 ETA；`warning`(P1) 当天；`info`(P2) 周会处理。

---

## 0. TravelAgentServerDown (P0)

**现象**：Prometheus `up{job="travel-agent-server"} == 0` 持续 3 分钟；用户侧整站不可用或所有接口超时。

**先区分两种"掉线"**：

| 情况 | 判据 | 处置 |
| --- | --- | --- |
| 进程真的挂了 | 容器/进程不存在，或 `/actuator/health` 本机也不通 | 按"服务崩溃"处理（下方） |
| 只是抓不到 | 服务正常，sh -c 'curl -s localhost:8080/actuator/health' 成功 | 按"抓取链路故障"处理 |

**排查（服务崩溃）**：
1. `docker ps -a` / `systemctl status` 看退出码与重启次数；`OOMKilled=true` → 内存问题。
2. 看最后 200 行日志：是否有 `OutOfMemoryError`、Flyway 迁移失败（`ddl-auto=validate` 时缺列会直接启动失败）、端口占用、数据库不可达。
3. 若刚发布过 → 直接按"回滚"走，不要现场调试。

**排查（抓取链路）**：
1. 抓取凭证是否过期（JWT 过期 → 401）：看 Prometheus targets 页面的错误信息，或 `curl -i` 抓取路径确认 401/403/404。
2. 端点 404 → `application.yml` 的 `management.endpoints.web.exposure.include` 缺 `prometheus`（见 observability.md §5.1）。
3. 网络/防火墙/反代变更。

**止血**：重启实例（`docker compose restart travel-agent-server`）；抓取凭证问题则刷新 token/改抓取配置，业务不动。
**回滚**：`docker compose up -d --no-deps <上一个镜像 tag>` 或回退上一版 jar；配置类问题回退 `application.yml`。
**升级**：连续两次重启仍崩溃、或数据文件损坏 → 升级到 DBA/平台负责人，同时保留现场（日志 + 堆转储 + 最后改动 diff）。

---

## 1. TravelPlanAvailabilityLow (P0)

> 同一小节覆盖 `TravelPlanOutcomeFailed`（失败速率告警）：它是本 SLO 的先行信号，处置路径相同。

**现象**：30 分钟窗口规划可用率 < 99.5%（`success+degraded` 占比）。用户表现：发消息后报错、长时间无响应、或完全拿不到方案。`degraded` 仍算可用，所以这条规则触发意味着**真的 failed**。

**确认不是误报**：
1. `travel_plan_outcome_total{result="failed"}` 是否在涨（速率 > 0.05/s 即"每分钟 3 次"）。
2. 抽样日志：`grep -E '规划|plan' <log> | tail -100`，按 `runId` 聚合看是否有堆栈。
3. 看是否有 5xx：`http_server_requests_seconds_count{status=~"5.."}` 的速率。

**排查顺序**（按概率）：
1. **最近发布/配置变更**：`git log`（只读）与容器镜像时间对比；90% 的规划失败集中在"刚改过 Planner/StructuredPlanner/预算配置"。
2. **规划依赖抛异常**：结构化规划的 LLM 适配层、RAG 检索、外部工具（高德/天气）在规划段被调用；看 WARN/ERROR 里的 `provider=`。
3. **数据库/事务**：规划会读行程与会话记忆；数据库连接池耗尽会同时打穿确认提交（见 §3）。
4. **预算配置被改小**：`travel.agent.budget.total-ms` 被调到很小 → 规划来不及，但注意那是 degraded 而非 failed。
5. **单实例问题 vs 全局**：`sum by (instance)` 看是否只有一台（滚动发布中的旧实例 / 单机磁盘满）。

**止血**：
- 有明确新版本 → 回滚（见下）。
- 依赖故障（如 LLM 供应商 402/5xx）→ 无需回滚：系统会自动降级到规则路径，此告警应转为观察降级率；若仍 failed，检查 `llm.calls{result=quota|skipped}` 与 Key 配置。
- 数据库连接池耗尽 → 重启实例恢复连接，同时限制入口流量（见 §15 限流不可用时的替代手段：反代层限速）。

**回滚**：回退上一个镜像/jar（规划是核心链路，禁止在线上直接改代码再重启）；若由配置触发，回退配置并 `POST /actuator/refresh` 不适用（非 config client）→ 重启。
**升级**：30 分钟内无法定位 → 升级后端负责人 + 值班主管；涉及数据损坏 → 同时升级 DBA 并暂停写路径（反代返回 503 或临时下线确认接口）。

---

## 2. TravelPlanDegradedHigh (P1)

**现象**：30 分钟窗口内超过 5% 的规划跳过研究/结构化规划；用户拿到"未经审核/模板化"的方案（回复里有"时间预算"提示、SSE 有 `budget_degraded` 事件）。

**排查**：
1. 先看是否"变慢导致降级"：`agent_stage_seconds` 的 plan/reflect P95，`llm_latency_seconds` 的均值。
2. `llm.calls{result="failed"}` 是否上升（模型不可用 → 结构化规划失败 → 规则路径）。
3. 外部工具（高德/天气）是否变慢：看 `external_degraded_total`（T3 已合入时）与工具日志。
4. 预算是否被调小：`AGENT_BUDGET_TOTAL_MS` 环境变量、`travel.agent.budget.total-ms`。

**止血**：调大总预算（`AGENT_BUDGET_TOTAL_MS=45000`，需评估端到端体验）或临时关掉审核/研究；若是 LLM 变慢，切备用供应商（改 `travel.llm.base-url/model` 后重启）。
**回滚**：预算/模型配置回退。
**升级**：持续 2 小时或与规划可用率同时恶化 → 升级后端负责人（P1 转 P0 判定）。

---

## 3. TravelConfirmSuccessRateLow (P0)

**现象**：10 分钟窗口确认提交成功率 < 99.5%（分母只含 `success+failed`，样本 > 5）。用户表现：点了"确认"，页面提示失败/一直转圈，行程没变化。

**确认不是误报**：
1. `travel_confirm_outcome_total` 的 success/failed 绝对值。
2. `conflict` 不应影响本告警——若 conflict 同时很高，说明是并发/版本竞争，不是提交故障（见 §4）。
3. 用户报障是否集中在某个 `planId`（方案已过期）而非全部（系统故障）。

**排查顺序**：
1. **提交事务**：`catch (RuntimeException)` 分支打的是 failed。查日志中 `确认`/`commit`/`Caused by` 的堆栈；常见是数据库约束/连接池/跨模块同步异常。
2. **跨模块同步**（日程/预算/足迹）失败不应导致 failed（它只 append 提示）——若 failed 集中在这里，说明异常发生在更早的 `executorAgent.commit`。
3. **风险门拒绝**（`review.blocked=true` 且未勾选"我已了解风险"）也会计 failed：这类是**预期行为**，若占比高说明前端没把风险确认框做对，属产品问题。
4. **方案过期**：用户停留过久（PendingPlan 有 TTL），批量过期会推高 failed，属于预期；看是否集中在长会话。
5. **数据库写失败**：`SHOW PROCESSLIST` / 连接池指标；磁盘满也会导致所有写失败。

**止血**：
- 数据库故障 → 重启实例恢复连接 + 只读降级（前端提示稍后重试）；必要时把确认接口临时下线（反代 503）避免持续失败。
- 代码/配置缺陷 → 回滚。
- 风险门误拦 → 临时把 `review.blocked` 判定阈值调宽（需产品确认），不要直接绕过审核。

**回滚**：回退镜像/jar；数据侧**不要**回滚已成功提交的行程（会与日程/预算快照不一致），需要修数据时先备份并只修失败的那部分。
**升级**：15 分钟内无恢复 → 升级后端负责人 + DBA；确认接口不可用超过 30 分钟 → 升级值班主管并按事故流程对外通报。

---

## 4. TravelConfirmConflictHigh (P2)

**现象**：30 分钟窗口冲突率 > 2%。用户可重试，数据不会错。

**排查**：看冲突来源（日志关键字）：并发抢占（同一方案被点两次/两个标签页）、内容指纹不符（前端缓存的方案已变）、行程版本已变（另一设备改过）。前端重复提交是首要嫌疑。
**止血**：前端按钮防抖/请求去重；无需后端回滚。
**升级**：持续高且伴随用户投诉 → 前端负责人；无需 P0 升级。

---

## 5. TravelSseAbnormalDisconnectHigh (P0)

**现象**：10 分钟窗口 `sse_disconnects{reason=~"error|timeout"}` 占 `/api/agent/stream` 请求数 > 1%。用户表现：AI 回复到一半断了、页面卡住后报网络错误。

**确认不是误报**：
1. 分母是否有流量（流请求数 > 0），否则规则里的守卫会挡掉——若仍触发请检查表达式是否被改动。
2. `reason` 拆开看：`timeout` 与 `error` 的处置不同（前者是超时链路，后者是异常）。
3. 是否只有单一实例/单一反代节点（`sum by (instance)`）。

**排查顺序**：
1. **反向代理/网关**：SSE 需要 `proxy_buffering off`、`proxy_read_timeout` 足够大（≥ 预算 30s + 余量）、HTTP/1.1。nginx 默认 60s 读超时会掐断长规划；若规划变慢，中断率随之升高。
2. **容器/探针重启**：`docker ps` 的 RestartCount、`OOMKilled`；存活探针把长连接所在实例重启。
3. **线程池耗尽**：SSE 异步执行器（配置的 pool）打满 → 新流排队后超时；看线程数与 queue 深度。
4. **客户端/网络**：`error` 里若全是 `Broken pipe`/`ClientAbortException`，实际是客户端侧断网或代理提前关闭（与 §6 一起看）。
5. **规划变慢**：`TravelPlanStageLatencyP95High` 同时触发 → 根因是慢，先治慢。

**止血**：
- 反代配置问题 → 改 `proxy_buffering off` 与 `proxy_read_timeout 300s` 后 reload（最快的止血，通常立即见效）。
- 实例频繁重启 → 回滚或扩容；探针参数临时放宽。
- 线程池耗尽 → 扩容实例 + 拒绝新流（返回 503 并提示稍后重试），避免雪崩。

**回滚**：回退最近的反代/应用变更；SSE 代码路径（AgentController/AgentEngine）改动优先回滚，不做现场热修。
**升级**：反代由平台/运维负责时同步升级；影响面 > 10% 用户 → 升级值班主管。

---

## 6. TravelSseClientAbortHigh (P1)

**现象**：客户端中断率（`reason="complete"`）> 10%。这通常是**用户在等待中关页面/切走**，不是服务故障。
**排查**：与 `TravelPlanStageLatencyP95High`、`llm.latency` 对照——变慢会让用户提前离开；再看前端是否有超时/重复发起逻辑。
**止血**：优先修慢（见 §9）；产品侧可加"预计等待时间/可离开稍后查看"提示。
**升级**：仅在伴随用户投诉或转化下降时升级产品负责人。无需回滚。

---

## 7. TravelExternalDegradedHigh (P1)

> 已实跑（2026-11-24）：高德持续 `connection_error` 下 `TravelExternalDegradedHigh`（`for: 10m`）与 `TravelExternalProviderDegraded{provider="amap"}`（`for: 15m`）均真实 firing 并在降级停止后恢复，见[演练快照](../review/2026-11-24-amap-degradation-alert-drill.md)。单 provider 那条从降级开始到 firing 实测 ≈16 分钟（`rate[15m]` + `for: 15m`），是「已持续降级」而非早期预警。

**现象**：外部依赖降级率 > 5%（每次 HTTP 请求的降级次数），或单 provider 持续 > 0.2 次/秒。用户表现：天气/路线/POI 变成估算值，方案质量下降但请求仍成功。

**排查**：
1. `sum by (provider, reason) (rate(external_degraded_total[10m]))` 定位是哪一家、什么原因。`reason` 词表固定为 `timeout` / `rate_limited` / `server_error` / `bad_response` / `connection_error`（高德的 `10021` QPS 超限与 `INVALID_USER_KEY` 都是 HTTP 200 + `status=0`，归入 `bad_response`）。
2. 对应 Key/额度：高德 Key 是否失效（`AMAP_KEY`）、天气源（Open-Meteo）是否可达、LLM 配额是否耗尽。
3. 是否只有外部网络故障（出网/DNS/代理）→ 用 `curl` 从容器内验证。
4. `no_key` 类降级说明配置缺失（部署问题），不是上游故障。

**止血**：切备用 Key/供应商；上游全挂时接受降级但**明确告知用户**（产品已有降级文案），不要伪造数据；必要时缩短超时让降级更快发生（改超时需重启）。
**回滚**：Key/超时配置回退。
**升级**：单 provider 故障持续 > 30 分钟 → 升级对应集成负责人；影响方案正确性（如路线全估算）→ 升级产品负责人决定是否暂停对外承诺。

---

## 8. TravelHttpLatencyP95High (P1)

**现象**：HTTP P95（排除 SSE）> 800ms 持续 10 分钟。

**排查**：
1. 按 `uri` 拆：`topk(5, histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri!~"/api/agent/stream"}[5m]))))`。
2. `/api/agent/chat`（同步规划）本来就慢（可达 30s 预算），它在 P95 里权重高时先看规划（§9），不要当普通接口优化。
3. 数据库慢查询、连接池等待、GC 停顿（看日志/`jstat`）、外部 HTTP 调用未设超时。
4. 流量突增 vs 单实例劣化（`sum by (instance)`）。

**止血**：扩容；对慢接口加/收紧超时与缓存；数据源问题切只读或降级。
**回滚**：最近变更回退。
**升级**：影响登录/行程列表等核心读路径 → 升级后端负责人。

---

## 9. TravelPlanStageLatencyP95High (P1)

**现象**：`agent_stage_seconds{stage="plan"}` P95 > 25s（总预算 30s 的 80%）。

**排查**：
1. 拆阶段：`stage="plan"` 慢通常是 LLM 结构化规划或研究循环；看 `llm.latency` 与 `agent.tool.loop` 的步数（`budget_exhausted` 多 = 步数打满）。
2. LLM 供应商侧变慢（`llm_latency_seconds` 均值上升、`llm.calls{result="failed"}` 超时增多）。
3. 外部工具（路线/天气/POI）串行调用变慢。
4. 结构化规划反复重规划（`structured.planning` 的 path/result 分布）。

**止血**：切更快的模型/供应商；临时降低研究步数上限；调大总预算只在"用户愿意等"时有效，通常先修慢源。
**回滚**：模型/预算配置回退。
**升级**：P95 > 25s 持续 2 小时或伴随规划降级率上升 → 升级后端负责人。

---

## 10. TravelLlmFailureHigh (P0)

**现象**：10 分钟窗口 LLM 失败率 > 10%（分母 `ok+failed`）。用户表现：回复变得模板化、"AI 不可用"式降级，规划仍能出结果但质量下降。

**确认不是误报**：
1. `llm.calls{result="failed"}` vs `{result="ok"}`；`skipped` 高说明是**没配 Key**（部署问题，不是失败）。
2. 日志：`LLM 调用失败，降级为规则模板: <根因>`（`ErrorSummary` 会给出 SocketTimeout/Connect/402 等具体类型）。
3. 是否 402（余额）/401（Key 失效）/429（限流）/5xx（供应商故障）/读超时。

**排查顺序**：
1. **Key 与余额**：供应商控制台看额度；`llm.calls` 的失败类型 + HTTP 状态。
2. **供应商状态**：是否有公开事故；用最小 `curl` 复现（带 Key 调 `/chat/completions`）。
3. **网络/DNS/出网**：容器内 `curl` 供应商域名；代理配置。
4. **超时设置过小**：`travel.llm.read-timeout-ms`（默认 30000）被改小。
5. **配额拦截误判**：`quota` 与 `failed` 是不同 tag，别混淆。

**止血**：
- Key 失效 → 换 Key 并重启（`LLM_API_KEY`）。
- 供应商故障 → 切备用 `base-url`/`model`（OpenAI 兼容，改配置重启），或接受规则降级并对外说明。
- 余额耗尽 → 充值；短期限制非核心调用（润色/审核）保住规划。
- 超时 → 临时调大 read timeout（需评估线程占用）。

**回滚**：最近改过模型/供应商/超时配置 → 回退并重启；不要为了"看起来正常"关掉 `llm.calls` 失败计数。
**升级**：30 分钟内无法切到可用供应商 → 升级后端负责人 + 采购/财务（余额）；影响付费用户 → 值班主管。

---

## 11. TravelLlmQuotaRejectedHigh (P2)

**现象**：30 分钟窗口配额拒绝率 > 5%（`llm.calls{result="quota"}`），说明 `travel.llm.quota-daily-limit` 生效中。
**排查**：配额是否配置过紧（正常用户被挡）或异常账号滥用（按 `userIdHash` 聚合日志）。注意无用户上下文的系统调用不受限。
**止血**：临时调大/关闭配额（`TRAVEL_LLM_...` 或配置后重启）；滥用账号按用户维度限流（§15）。
**升级**：产品/安全负责人决定配额策略。

---

## 12. TravelLlmCostBudgetHigh (P1)

**现象**：`travel_llm_cost_usd_total` 折算 > 2 USD/h（单实例）。单价未配置（默认 0）时该指标不存在，本告警不会触发——这是刻意的"不虚报"。
**排查**：
1. `sum by (type) (rate(travel_llm_tokens_total[1h]))` 看 prompt/completion 谁涨。
2. prompt 暴涨通常是上下文变长（会话历史/研究观察）或重试风暴；completion 暴涨是输出变长。
3. `llm.calls` 总量与失败/重试；是否有异常账号（按 `userIdHash` 聚合）。
4. 单价配置是否刚被改过（成本是"用当前单价 × token"的历史累计，改单价会让曲线跳变——记录变更时间）。

**止血**：限制非核心 LLM 调用（润色/审核降级）、收紧配额、降低历史条数/研究步数。
**回滚**：回退导致 token 暴涨的提示词/步数配置。
**升级**：成本超过预算负责人设定阈值 → 升级后端负责人 + 财务/产品；异常刷量同时升级安全。

---

## 13. TravelRagHitRateLow (P1)

**现象**：30 分钟窗口 RAG 命中率 < 30%（样本 > 10）。用户表现：问答频繁回"知识库暂无可靠资料"，方案里少了知识引用。

**排查**：
1. 是"真没资料"还是"索引坏了"：看启动日志 `RagBootstrap` 是否加载了 chunk、`/api/knowledge` 的文档/版本列表是否正常。
2. Embedding：`OpenAiCompatibleEmbeddingService` 失败会 WARN；本地哈希实现（默认）不依赖网络，命中率突变更像索引/数据问题。
3. 版本回滚后是否忘了 `reload()`（`RagVersionService` 切换版本后内存索引需要重建）。
4. 用户 query 是否整体变偏（新城市/新品类）——这类是数据覆盖问题，不是故障。

**止血**：重启触发索引重建（`RagBootstrap`）；重新导入缺失知识；如需临时提高命中，放宽 `travel.rag.min-score`（只影响问答层，不影响检索指标——注意指标记的是检索，不是问答）。
**回滚**：回退知识库版本切换/导入批次。
**升级**：索引无法加载（异常）→ 升级后端负责人；数据覆盖问题 → 产品/内容负责人。

---

## 14. TravelLowHistogramMissing (P1)

**现象**：`absent(http_server_requests_seconds_bucket)` 持续 15 分钟——P95 面板为空、延迟告警永不触发。**这不是业务故障，但会让监控失去眼睛**，按 P1 处理。

**排查**：
1. `curl .../actuator/prometheus | grep '_seconds_bucket'` 是否为空。
2. `application.yml` 是否缺 `management.metrics.distribution.percentiles-histogram.http.server.requests: true`（agent.stage / llm.latency 同理）。
3. `management.endpoints.web.exposure.include` 是否包含 `prometheus`。
4. 是否刚升级 Spring Boot / 换 registry 实现导致指标名变化（对比 `observability.md §2`）。

**止血**：补配置并重启（配置不能热加载）。**回滚**：无需回滚业务，回退监控配置变更。**升级**：配置由 T1/平台 owner 合并 → 升级对应负责人；期间人工用 `/actuator/metrics/<name>` 的 max/mean 做粗判。

---

## 15. TravelRateLimitFailOpen (P1)

**现象**：15 分钟内 `ratelimit_failopen_total` 增长——Redis 不可用，限流放行（登录/上传/Agent 配额暂时失效）。
**排查**：Redis 连通性（`redis-cli ping`）、连接池、`travel.rate-limit.store` 配置、Redis 内存/淘汰策略（`maxmemory-policy` 导致 KEY 丢失不是 fail-open，但会让计数失真）。
**止血**：切 `RATE_LIMIT_STORE=memory`（单实例内存限流，立即恢复保护；多实例下配额不共享，属可接受折中），修好 Redis 后切回。反代层可临时加 IP 级限速兜底。
**回滚**：配置回退。**升级**：Redis 由平台负责 → 升级平台/DBA；确认无滥用流量后降级为 P2 观察。

---

## 附录 A：日志脱敏核对（P0-D）

目标：JWT / Cookie / API Key / 手机号 / 照片与 SSE 票据不出现在可被运维、外包或日志平台看到的日志里。**当前状态是“部分成立 + 两处已知缺口”**，发布前必须至少完成一次核对并记录。

### A.1 当前事实（按代码与配置核对）

| 检查面 | 现状 | 证据 |
| --- | --- | --- |
| 后端日志格式 | `级别 [traceId] 消息`；`TraceIdFilter` 只写 MDC 与响应头，不记录请求头/请求体 | `application.yml` `logging.pattern.level`、`TraceIdFilter` |
| 请求 URI | 后端用 `request.getRequestURI()`（不含 query string），不会把 `?ticket=` 写进业务日志 | `JwtAuthenticationFilter`、`RateLimitFilter` |
| JWT / Cookie | 代码未打印 `Authorization` 头、Cookie 或 token；`JwtService` 只警告“未配置密钥” | `JwtAuthenticationFilter`、`JwtService` |
| LLM Key | `LlmModelProbe` 只记录 provider host 与模型名；失败日志走 `ErrorSummary`（异常类型/状态），不打请求头 | `LlmModelProbe`、`OpenAiCompatibleLlmClient` |
| 手机号 | **数据模型当前没有手机号字段**（账号只有 username/nickname/password hash），暂无手机号泄漏面；未来新增字段必须补脱敏规则 | `User.java` |
| 照片原始文件名 | 会落库 `trip_photo.original_name`（可能含用户自定文件名）；不属于日志，但属于用户数据，删除流程需覆盖 | `PhotoService`、数据删除流程 |
| **Redis 限流 fail-open** | WARN 日志带 `bucket=...`，bucket 由「作用域 + identity + 方法」拼成，identity 是**用户名或 IP** → **已知脱敏缺口** | `RedisRateCounter#failOpen` |
| **nginx 访问日志** | `travel-agent-web/nginx.conf` 的 `log_format without_args`（只记 `$request_method $uri $server_protocol`，**不记查询串**）已修正为 **`http` 上下文（位于 `server {` 之前）**，`access_log ... without_args` 仍在 server 内 → **配置已修正；仍缺 `nginx -t` / 真实容器实测**。外层 TLS/云 LB 仍需同样配置，否则外层日志仍可能记 `?ticket=` | `travel-agent-web/nginx.conf` |
| 异常日志 | `GlobalExceptionHandler` 记录异常消息与堆栈；上游错误可能含 URL/主机，不含 Key（Key 不在异常消息里） | `GlobalExceptionHandler` |
| H2/DB 口令 | 只在环境变量/compose 中，不打印 | `application*.yml` |

### A.2 核对步骤（每次上线前执行一次）

```bash
# 1) 后端：搜密钥、令牌、票据、口令、手机号特征
docker compose logs backend --tail=5000 | grep -Ein \
  'authorization|bearer |set-cookie|travel_agent_token|ticket=|api[_-]?key|secret|password|1[3-9][0-9]{9}'

# 2) nginx：确认不再记录查询串（已改为 log_format without_args；若仍命中 ticket= 说明配置未生效或外层代理未改）
docker compose logs web --tail=5000 | grep -Ein 'ticket=|authorization|set-cookie'

# 3) 触发一次照片读取/SSE，再重复 1)2)，确认新增日志没有票据
#    照片 URL 形如 /api/photos/<id>/raw?ticket=...；从浏览器 Network 取，不要复制到工单
```

判读：

- 命中 `未配置环境变量 JWT_SECRET`、`演示账号已禁用` 这类**配置事实**属正常，不算泄漏。
- 命中真实 `ticket=`（nginx）或 `bucket=<用户名>`（Redis fail-open）即**未通过**；nginx 侧先确认 `log_format without_args` 已生效（`nginx -t` + 重建/重启容器），外层代理同样处理，再记录风险接受人。
- 不要为了“清零 grep”关掉错误日志；要改的是日志内容，不是可见性。

### A.3 缺口处理建议

| 缺口 | 建议修法 | 归属 |
| --- | --- | --- |
| nginx access log 记录 `?ticket=` | **配置已修正（http 上下文），仍待验证**：`nginx.conf` 的 `log_format without_args` 已移到 `server {` 之前、`access_log` 仍在 server 内；继续执行 `nginx -t`、重建 web 容器、确认 `docker compose logs web` 不再出现 `ticket=`。外层 TLS/云 LB 也要同样配置 | 前端/运维 |
| Redis fail-open 日志带用户名/IP | 只记作用域与 identity 的哈希/类别（user/ip），或把 identity 从日志中移除；保留告警能力 | 后端单独 PR |
| 日志保留期未书面化 | 在部署侧配置 Docker 日志轮转（`max-size`/`max-file`）并写入运维手册 | 运维 |
| gitleaks 未进 CI | 在 `security` job 增加 gitleaks 步骤（需 CI 维护方评审） | CI |

## 附录 B：限流与 LLM 配额验证（P0-D）

### B.1 限流（`RATE_LIMIT_*`）

前提：**不要**对 e2e 隔离后端验证（CI 的 e2e 后端显式 `RATE_LIMIT_ENABLED=false`）。用预发或专用实例，并确认 `rateLimitEnabled=true`。

```bash
BASE=http://127.0.0.1:8081
# 1) 连续打登录接口，观察第 N 次是否 429（N 取 RATE_LIMIT_AUTH，默认 10/300s，IP 维度）
for i in $(seq 1 15); do
  curl -sS -o /tmp/rl.body -w "%{http_code} " -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' -d '{"username":"<账号>","password":"wrong"}'
done; echo
# 2) 期望：前若干次 401，之后出现 429；429 响应带 Retry-After，且 JSON message 含“操作过于频繁”
curl -sS -D - -o /dev/null -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"<账号>","password":"wrong"}' | grep -Ei 'HTTP/|retry-after'
```

验收：

- [ ] `RATE_LIMIT_AUTH` 次之后出现 `429`，`Retry-After` ≥ 1。
- [ ] `/api/system/status` 的 `rateLimitEnabled=true`。
- [ ] 反代部署时 `RATE_LIMIT_TRUSTED_PROXIES` 命中真实反代网段；未配置时会出现“所有用户共用一个桶”的 429（见 [生产配置清单](production-config.md) §4）。
- [ ] `RATE_LIMIT_STORE=redis` 时，重启一个实例后计数仍共享；Redis 断开时 `ratelimit_failopen_total` 增长且请求被放行（fail-open 是预期）。

### B.2 LLM 每日配额（`LLM_DAILY_QUOTA`）

前提：在预发把 `LLM_DAILY_QUOTA` 设为一个小值（如 `2`）并确认它真的进入容器（compose 默认不透传，见配置清单 §5），然后重启。

```bash
# 1) 登录并查看配额
curl -sS -c cookies.txt -X POST http://127.0.0.1:8081/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"<账号>","password":"<密码>"}'
curl -sS -b cookies.txt http://127.0.0.1:8081/api/system/status | grep -o '"llmQuota[^,]*'

# 2) 触发多次真实 LLM 调用（每次都要超过配额），观察降级与指标
curl -sS -b cookies.txt "http://127.0.0.1:8081/actuator/metrics/llm.calls"
# 3) 数据库侧核对（MySQL 模式）
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" travel_agent -e \
  "SELECT user_name, usage_date, used_count FROM llm_quota_usage ORDER BY used_count DESC LIMIT 10;"
```

验收：

- [ ] 超过配额后 `llm.calls{result="quota"}` 增长，回复降级为规则模板，**不报 5xx**。
- [ ] `llmQuotaUsed/llmQuotaLimit` 与实际计数一致（配额为 0 时不返回这两个字段）。
- [ ] 校验完把配额改回生产值（或删除测试数据），并在变更记录里写明。

### B.3 状态接口与模型探测

```bash
curl -sS -b cookies.txt http://127.0.0.1:8081/api/system/status \
  | grep -oE '"llm(ModelAvailable|ModelError|ModelCheckPending|Provider|Verified|Configured)"[^,]*'
```

判读：`llmModelAvailable=true` 才表示模型在清单内；`false` 时看 `llmModelError`；`null` 表示未校验（探测关闭/未配 Key/探测失败），**不能当成可用**。

## 附录 C：告警矩阵补充（与 `alerts.yml` 对齐）

现有规则（`alerts.yml`）覆盖规划、确认、SSE、外部依赖、延迟、LLM、RAG、限流。以下 P0-D 关注项**还没有对应指标或规则**，先人工核对，指标补上后再加规则（不要在指标缺失时写“已覆盖”）。

| 关注点 | 现有指标 / 告警 | 状态 | 缺口与建议 |
| --- | --- | --- | --- |
| LLM 失败率 | `llm_calls_total{result="failed"}` / `TravelLlmFailureHigh` | ✅ 已覆盖 | — |
| LLM 配额拒绝 | `llm_calls_total{result="quota"}` / `TravelLlmQuotaRejectedHigh` | ✅ 已覆盖 | — |
| 外部依赖降级 | `external_degraded_total{provider,reason}` / `TravelExternalDegradedHigh`、`TravelExternalProviderDegraded` | ✅ 已覆盖 | `10021` 被归到 `reason="bad_response"`，与无效 Key 混在一起 |
| **MCP 工具数为 0** | 无指标；`/api/system/status` 有 `mcpToolCount`、`mcpLastError` | ⚠️ **待补指标** | 建议加 gauge（如 `mcp.tools.registered{server}`）；在补上前，用启动后巡检脚本查 `mcpToolCount` 并在为 0 时告警（查询需登录） |
| **高德 10021 比例** | `external_degraded_total{provider="amap"}` **只覆盖地理编码**（`AmapGeocodeClient`）；POI/路线走 `AmapDataService`，只打 WARN 日志、不记该指标 | ⚠️ **待补指标** | 10021 在 geocode 侧被归到 `reason="bad_response"`，无法与无效 Key 区分；建议单独记 `amap.qps_limited`（POI/路线/REST/MCP 统一），阈值参考：5 分钟比例 > 1% 告警 |
| **备份失败** | 无指标 | ⚠️ **待补** | `scripts/backup-drill.ps1` 退出码可接 cron/systemd；建议外部监控记录“最近成功时间”，超过 26 小时告警 |
| **磁盘容量** | 无应用指标 | ⚠️ **待补/外部** | 用 node_exporter `node_filesystem_avail_bytes` 或宿主监控；阈值参考 [部署与运维 §11.2](../guide/deployment.md)（磁盘 > 80%） |
| **照片容量** | 无指标；可查 `SELECT SUM(size_bytes) FROM trip_photo` | ⚠️ **待补指标** | 建议加 gauge（按用户/全局），或在备份巡检里顺带查；接近 `PHOTO_MAX_BYTES_PER_USER` 时提醒 |
| **账号注销失败/残留** | 无指标；日志 `账号删除完成`、`账号注销照片文件清理失败` | ⚠️ **待补指标** | 建议 counter（按结果/表失败数）；补指标前按附录 D 人工核对。照片清理失败只 WARN，必须巡检 |
| 规划 / 确认 / SSE / RAG / 延迟 | 见 `alerts.yml` | ✅ 已覆盖 | — |

### C.1 告警落地检查（Prometheus Operator / 裸 Prometheus 通用）

- [ ] `alerts.yml` 里 `runbook_url` 的 `OWNER` 占位已替换为真实组织名。
- [ ] Prometheus 抓取目标 `job="travel-agent-server"` 能抓到 `/actuator/prometheus`（需登录；抓取凭证见 [可观测性](../guide/observability.md)）。
- [ ] `TravelLowHistogramMissing` 未触发（否则 P95 类告警静默失效）。
- [ ] 新增/修改规则后 `promtool check rules` 通过（如环境有 promtool）。
- [ ] 每条 P0 规则都有值班接收人；用一次演练告警确认能送达（不要只配规则不验证）。

## 附录 D：账号注销后仍有残留（核对步骤）

**触发**：用户反馈“已注销但仍能查到账号/数据”，或巡检发现 `账号删除完成` 与预期不符。当前**没有专用指标**，按本节人工核对；先止损/取证，不要直接重跑注销。

### D.1 先确认注销是否真的成功

| 现象 | 判读 |
| --- | --- |
| 响应 `400` `请输入密码确认注销` | 没有删除，登录态仍在；引导用户重新输入密码 |
| 响应 `401` `密码错误` | 没有删除；不要当“登录失效”踢下线 |
| 响应 `401` 未登录/用户不存在 | 未登录或已经删过；查 `users` 是否已无该 id |
| 响应 `200` + `Set-Cookie ... Max-Age=0` | 删除事务已提交；继续 D.2 |

日志证据（不写用户名/密码/文件名）：

```bash
docker compose logs backend --tail=5000 | grep -E '账号删除完成|账号注销照片文件清理失败'
# 形如：账号删除完成: userId=123, 删除行数=42, 照片文件数=2
```

### D.2 数据库残留

用工单里的 `userId`（不要用用户名模糊匹配）：

```sql
SELECT COUNT(*) FROM users WHERE id = <userId>;
-- 逐表核对（与 data-deletion §2 一致；users 已为 0 时仍要查子表）
SELECT 'trip' t, COUNT(*) c FROM trip WHERE user_id=<userId>
UNION ALL SELECT 'trip_photo', COUNT(*) FROM trip_photo WHERE user_id=<userId>
UNION ALL SELECT 'conversation_message', COUNT(*) FROM conversation_message WHERE user_id=<userId>
UNION ALL SELECT 'agent_run', COUNT(*) FROM agent_run WHERE user_id=<userId>
UNION ALL SELECT 'llm_quota_usage', COUNT(*) FROM llm_quota_usage WHERE user_name='<username>'
UNION ALL SELECT 'rag_document', COUNT(*) FROM rag_document WHERE user_id=<userId>;
```

- 若 `users` 已 0 但子表 >0：记录表名/行数，按 [数据删除流程](../legal/data-deletion.md) §4 **只补删残留**（先备份、双人复核），不要重跑整笔注销。
- 若 `users` 仍存在：注销未成功（回到 D.1），不要手工删一半。

### D.3 照片 / S3 / 内存 RAG / Redis

- **照片**：日志有 `账号注销照片文件清理失败` 时，数据库已无 `stored_name` 可反查；用对象存储清单做孤儿扫描（与 `trip_photo` 全集比对），按运维策略删除。S3 的**版本控制/生命周期/备份中的历史对象**不会自动消失，需按存储策略处理。
- **内存 RAG**：V24 起注销事务提交后会自动 `vectorStore.reload()`；若日志出现 `账号注销后重建内存向量索引失败`，重启 backend 即自愈（启动会全量重建）。这不是“删库失败”。
- **Redis**：`SCAN 0 MATCH ratelimit:user:<username>* COUNT 100`；有残留可 `DEL`，或等 300 秒 TTL 自然过期。

### D.4 旧 JWT / 同名重注册

- 旧 JWT 携带 `uid` + `ver`，过滤器会校验用户存在与 `token_version`；注销后旧 token 应一律 `401`。若某个接口仍返回 200，按安全缺陷升级，不要当普通残留。
- **同名重新注册不会再复用旧 token**：新账号得到新的自增 uid，旧 token 的 uid 查不到用户。不需要墓碑或禁用用户名。
- 需要吊销某用户全部 token 时：`UPDATE users SET token_version = token_version + 1 WHERE id = ?`（注销路径已自动执行）。操作前确认影响面、备份并留记录。

### D.5 日志 / 备份

- 应用日志与 nginx 访问日志不应含被删内容；nginx 已用 `without_args`（待容器验证）。`docker compose logs web | grep 'ticket='` 应无命中。
- **备份库仍可能有历史数据**，这是预期而非残留故障；按备份保留周期过期，不要为了“清零”删备份。
- 取证只保留计数、userId、traceId、对象清单，不复制正文/照片。

---

## 附：发布与回滚检查单

- [ ] 发布前：`mvn -o -q -DskipTests package` 与关键单测通过（含 `*MetricsTest`）。
- [ ] 发布后 5 分钟：`/actuator/health` UP；`/actuator/prometheus` 含 `travel_*` 与 `*_seconds_bucket`；Dashboard 有数据。
- [ ] 发布后 15 分钟：规划可用率、确认成功率、SSE 异常中断率、LLM 失败率四条 P0 曲线无异常抬升。
- [ ] 回滚：只回退应用镜像/jar（不碰数据库结构）；Flyway 迁移是单向的，涉及迁移的发布必须确认"回滚到旧版本仍能读新结构"（`ddl-auto=validate` 会直接拒绝启动）。
- [ ] 事故后：把新发现的判据补进本手册对应小节，并同步 `alerts.yml` 的 `for`/阈值。