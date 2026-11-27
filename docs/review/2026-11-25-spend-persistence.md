# 每轮花费落库（`agent_run_spend`，V29）

> 快照日期：2026-11-25 ｜ 范围：P2 花费预算的持久化与查询契约
> ｜ 结论：**每轮 run 收尾写一条花费快照；`GET /api/agent/runs/{runId}` 直接返回 `spend`；
> 随 Agent 追踪保留期清理**。定向测试 10/10，后端完整 `mvn verify` 见 §4。
> ｜ 动因：[花费预算评审](2026-11-25-spend-budget.md) §5 第 2 项——此前花费只写一行 INFO 日志，
> 日志会滚动、指标不能带 runId（基数炸弹），执行台账回答不了「这一轮花了多少、卡在哪一项」。

## 0. 结论速览

| # | 问题 | 结论 |
| --- | --- | --- |
| 1 | 能不能按 runId 查到本轮花费 | 能：`agent_run_spend.run_id` 唯一，一行就是一轮 |
| 2 | 记了什么 | LLM 调用数 / 工具调用数 / 重试数 / prompt+completion token / 估算费用 / 触顶项 |
| 3 | 会不会重复记账 | 不会：runId 唯一，重复收尾按后写覆盖（快照只会更完整） |
| 4 | 落库失败会不会影响用户 | 不会：服务内部 catch + WARN，本轮结果与追踪主流程不受影响 |
| 5 | API 能不能直接看 | 能：`GET /api/agent/runs/{runId}` 新增 `spend`（未启用花费预算或落库失败时为 `null`） |
| 6 | 表会不会无限增长 | 不会：随 Agent 追踪 14 天保留期清理（`AgentTraceService.purgeExpired`） |
| 7 | 历史 run 有没有补数据 | 没有：迁移不回填，旧 run 的 `spend` 为 `null`；`null` 与「花了 0」在契约上可区分 |

## 1. 背景

花费预算（`AgentSpend`）落地后，每轮收尾会打一行：

```
Agent 本轮花费: runId=… llm=8/30 tool=12/200 retry=0/5 tokens=17641/200000 cost=$0.0021/$1.0000 exhausted=none
```

它足以回答「这一轮有没有到顶」，但回答不了台账问题：

- 日志有滚动与保留期，过了就查不到；
- `travel.agent.spend` 指标只有 `result/limit` 两个枚举 tag，**不能**把 runId/金额放进 tag（基数炸弹，
  也是本项目指标纪律明令禁止的）；
- `travel.llm.tokens` / `travel.llm.cost.usd` 是全局累计，无法拆回单轮。

数据结构上，`agent_run` 是「一次对话 = 一次 run」的权威台账，自然应该挂一张一对零或一的快照表。

## 2. 设计

### 2.1 表

`V29__agent_run_spend.sql`（双方言中性 SQL，H2 / MySQL 8 均可）：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `run_id` | `VARCHAR(64)`，唯一 | 对应 `agent_run.run_id`；一次运行一条 |
| `user_id` | `BIGINT` | 冗余存一份，按用户查花费不必回表 |
| `llm_calls` / `tool_calls` / `retries` | `INT NOT NULL DEFAULT 0` | 真实调用次数（失败/超时也计入，与预算口径一致） |
| `prompt_tokens` / `completion_tokens` | `BIGINT NOT NULL DEFAULT 0` | 供应商返回的 usage；没有 usage 记 0，不估算 |
| `cost_usd` | `DECIMAL(18,6)` | 按配置单价折算；未配价目表恒为 0 |
| `exhausted` | `VARCHAR(20)` | `AgentSpend.Limit` 的 tag；未触顶为 NULL |
| `created_at` | `DATETIME NOT NULL` | 快照写入时间；保留期清理依据 |

索引：`uk_agent_run_spend_run (run_id)`、`idx_agent_run_spend_created (created_at)`、
`idx_agent_run_spend_user (user_id, created_at)`。

### 2.2 写入与读取

```
AgentEngine.finish → recordSpend(ctx)
  ├─ log.info      （原有：即时可读）
  ├─ travelMetrics.spendOutcome(snapshot)   （原有：低基数指标）
  └─ AgentRunSpendService.record(runId, userId, snapshot)   ← 新增（V29 落库）
```

- 只在 `spend.isEnabled()` 时写：未启用花费预算的入口（渠道、单测）行为与升级前一致，也不会留下全 0 行；
- `record` 先按 runId 查、存在则更新：一次运行正常只收尾一次，幂等覆盖用于兜底；
- 写入失败（DB 不可用/H2 锁等待）只 WARN，**不**让接口 5xx——花费是补充信息；
- 读取失败按「无记录」处理（详情接口的 `spend=null`），同样不影响主契约。

### 2.3 API 与保留期

- `GET /api/agent/runs/{runId}` 的 `RunDetail` 新增 `spend`（OpenAPI `SpendView`）：
  `llmCalls` / `toolCalls` / `retries` / `promptTokens` / `completionTokens` / `totalTokens` /
  `costUsd` / `exhausted`；
- `totalTokens` 是展示用的派生值（输入+输出），不落列，避免两处口径不一致；
- `AgentTraceService.purgeExpired` 增加 `agent_run_spend` 按 `created_at` 的清理，与 run/task/trace/event/stage
  在同一独立事务里删除（14 天，默认保留期）。

## 3. 口径边界

- **不回填历史**：迁移只建表。升级前结束的 run 没有快照，`spend=null`；
  `null` 明确表示「没有记录」，与「记录了 0 次调用 / 0 成本」区分得开；
- **成本仍依赖价目表**：未配置 `travel.llm.*-price-per-1k-usd` 时 `cost_usd` 为 0，
  这是刻意的（不猜测供应商价格），不是漏记；
- **不做按用户/时间段的对外汇总接口**：本期只落台账 + 单 run 查询；
  运营汇总走 SQL（示例见 [observability](../guide/observability.md) §2.4），
  避免在没有真实需求前先长出分页/聚合契约；
- **不做多实例分摊**：快照由收尾所在实例写入，一行一个 run，天然无并发分摊问题。

## 4. 验证证据（2026-11-25，本机）

定向测试：

```
mvn -B -Dtest='AgentRunSpendTest,AgentSpendIntegrationTest,AgentTraceApiTest' -DfailIfNoTests=false test
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

后端完整门禁：`mvn -B verify` → **718 tests, 0 failures / 0 errors / 3 skipped**，
JaCoCo 覆盖率检查通过、Checkstyle 0、SpotBugs 0；`MigrationBaselineTest` 的版本面已含 V29。

覆盖点：

- `AgentRunSpendTest`（4）：字段完整落库、同 run 幂等覆盖、空 runId/空快照不写、保留期只删旧行；
- `AgentSpendIntegrationTest`（2）：真实 `/api/agent/chat` 在工具次数触顶后，能从台账查回
  `tool_calls>=2`、`exhausted=tool_calls`、`user_id` 正确；
- `AgentTraceApiTest`（4）：规划 run 的详情 `spend.toolCalls>=3`、`exhausted=null`；闲聊 run 也有一行（toolCalls=0）。

契约：`OpenApiSnapshotTest` 重新生成快照，新增 `SpendView` schema（paths=56，schemas=114），无其它漂移。

## 5. 未验证 / 边界

- **真实 MySQL 迁移**以 CI `mysql-migration` job 为准（V29 使用双方言中性 DDL，与 V25–V28 同风格）；
- **生产数据量下的保留期清理**未压测：按 `created_at` 单列删除，索引已建；
- **前端展示**：本轮只做后端契约，`AgentRunsDrawer` 尚未渲染 `spend`（属于 UI 增量，不阻塞口径落地）。

## 6. 下一步

1. 阶段三：Outbox / ABANDONED / 租约清扫的三个低基数指标与告警（§5 第 4 项）；
2. 真实 Embedding 与 POI 质量验收（§5 第 3 项，需要真实 Key）；
3. 前端运行详情展示花费快照（可选，等 UI 批次）。