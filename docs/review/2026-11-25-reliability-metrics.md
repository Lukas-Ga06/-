# 阶段三可靠性指标与告警（Outbox / ABANDONED / 租约清扫）

> 快照日期：2026-11-25 ｜ 范围：P0 可靠执行链路目前只有日志/查库的三类信号，补低基数指标与告警
> ｜ 结论：**3 个指标 + 4 条规则落地**，`promtool check rules` 通过（22 条 / 9 组），
> 定向 15/15；后端完整 `mvn verify` 见 §4。
> ｜ 动因：[花费预算评审](2026-11-25-spend-budget.md) §5 第 4 项：阶段三 Outbox / ABANDONED / 租约清扫
> 「目前仍只有日志」——告警无法 PromQL，只能靠人翻日志。

## 0. 结论速览

| # | 此前 | 现在 |
| --- | --- | --- |
| 1 | Outbox 积压/DEAD 只能查 `plan_outbox` 表 | `outbox_status{status="pending\|dispatching\|done\|dead"}`（Gauge，抓取时现查） |
| 2 | ABANDONED 只在恢复任务日志里 | `agent_run_abandoned_total`（Counter，无 tag） |
| 3 | 租约清扫只在日志里 | `pending_plan_swept_total`（Counter，无 tag） |
| 4 | 无对应告警 | `TravelOutboxDeadPresent`（critical）、`TravelOutboxBacklogHigh`、`TravelAgentRunAbandoned`、`TravelPendingPlanSweptHigh`（warning） |
| 5 | 阈值无依据 | 初值可运营：DEAD>0 持续 10m；pending>100 持续 15m；30m 内 ABANDONED>0；30m 内清扫>5；上线一周后按真实积压回填 |

## 1. 指标设计

### 1.1 `outbox.status`（Gauge）

- Micrometer `outbox.status` → Prometheus `outbox_status`，tag `status=pending|dispatching|done|dead`，4 条序列封顶；
- **抓取时现查**：`PlanOutboxRepository#countByStatus` 走 `(status, next_attempt_at)` 索引前缀；
  刻意不用「投递器每轮顺手记数」——投递器停摆时后者会冻结在最后一个值，面板看起来一切正常；
- DB 不可用时采样为 **NaN**，不是 0：`> 0` 告警不会误报，曲线上表现为断点；
- 多实例：每个实例都查同一张表，告警用 `max()` 而不是 `sum()`，避免把同一份积压乘以实例数。

### 1.2 两个 Counter

- `agent.run.abandoned`：`AgentRunRecoveryJob#recoverOnce` 每判一批 `+recovered`；
  与执行体自报 `FAILED` 区分——ABANDONED 是「没人知道结果」；
- `pending.plan.swept`：`PendingPlanRecoveryJob#sweep` 每清扫一批 `+swept`；
  持续非 0 说明确认提交在崩溃/超时，而不是「租约调太小」；
- 两者都是无 tag 单序列；`0` 不创建指标（没有事件时不伪造一条 0 线）。

## 2. 告警规则（[alerts.yml](../operations/alerts.yml) §8 `travel-agent.reliability`）

| 告警 | 表达式 | for | severity | 处置 |
| --- | --- | --- | --- | --- |
| `TravelOutboxDeadPresent` | `max(outbox_status{status="dead"}) > 0` | 10m | critical | 确认后同步已死；查 `last_error/attempts`，修复根因后补投 |
| `TravelOutboxBacklogHigh` | `max(outbox_status{status="pending"}) > 100` | 15m | warning | 投递器停摆或下游持续失败 |
| `TravelAgentRunAbandoned` | `increase(agent_run_abandoned_total[30m]) > 0` | 5m | warning | 崩溃/重启/OOM；结果未知，提示重试 |
| `TravelPendingPlanSweptHigh` | `increase(pending_plan_swept_total[30m]) > 5` | 10m | warning | 确认提交超租约；查 DB 延迟与提交耗时 |

每条都带 `summary` / `description` / `runbook_url`，对应 [Runbook](../operations/runbook.md) §16–§19；
severity 沿用既有路由（critical→oncall-critical、warning→oncall-group），不新增路由分支。

## 3. 验证证据（2026-11-25，本机）

```
node scripts/alerts-rules-extract.mjs -Out .evidence/alerts-rules.yml --summary
[ OK ] 9 组 / 22 条规则
.tools/prometheus/.../promtool.exe check rules .evidence/alerts-rules.yml
SUCCESS: 22 rules found
```

定向测试：

```
mvn -B -Dtest='OutboxStatusMetricsTest,TravelMetricsTest,AgentRunRecoveryJobTest,PendingPlanLeaseTest,PlanOutboxDispatcherTest' test
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

覆盖点：

- `OutboxStatusMetricsTest`（3）：四条 Gauge 的 tag/值、两次抓取走两次查询（不冻结）、DB 失败为 NaN 而非 0；
- `TravelMetricsTest`（9）：两个 Counter 按 N 累加、0 不创建序列；
- `AgentRunRecoveryJobTest`（3）：真实恢复后 `agent_run_abandoned_total` 增量等于 recovered；
- `PendingPlanLeaseTest` / `PlanOutboxDispatcherTest`：既有租约与投递语义不受埋点影响。

后端完整门禁：`mvn -B verify` → **722 tests, 0 failures / 0 errors / 3 skipped**，
JaCoCo 覆盖率检查通过、Checkstyle 0、SpotBugs 0。

## 4. 未验证 / 边界

- **阈值是初值**：100 条积压、5 次/30 分钟清扫没有真实流量基线；上线第一周按 §3 SLO 验证命令回填；
- **Gauge 抓取成本**：4 条 `count(*)`/抓取，当前数据量可忽略；`plan_outbox` 增长到百万级时需要复核索引与统计策略；
- **未做 Alertmanager 端到端演练**：新规则只验证 Prometheus 解析与 `severity` 标签；送达链路沿用
  [告警路由演练](2026-11-25-alert-routing-drill.md) 的结论（真实 webhook 仍缺）；
- **未加 MCP 指标**：`mcp.tools.registered`/调用失败率仍只在 `/api/system/status` 与审计日志里
  （见 [Runbook](../operations/runbook.md) 附录 C 的缺口清单）。

## 5. 下一步

1. 真实 Embedding 与 POI 质量验收（花费评审 §5 第 3 项，需要真实 Key）；
2. MCP 指标化（工具数/调用失败率，配合逐 server 治理的白名单/熔断/配额）；
3. 按真实积压回填可靠性阈值，并把 Outbox DEAD 的补投操作固化成脚本。