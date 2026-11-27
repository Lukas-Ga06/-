# P0 检查清单现实核对（2026-11-25）

> 目的：把「四阶段 20 项 + 9 类测试 + 5 个验收用例」的提案，逐条对照 `main` 上的真实代码，
> 分清**已经做过**、**只做了一半**、**确实没做**。结论按证据说话，不按提案措辞。
>
> 核对基线：`ab00538`（`main`），后端 22 个 Flyway 迁移、148 个测试类、21 张表。
> 文中路径均相对仓库根目录。

## 1. 一句话结论

提案的第一阶段（P0）**不是从零开始**：5 项里有 4 项已有主体实现，其中「Confirm 并发安全」
和「PendingPlan 持久化」比提案假设的更完整。真正还缺的两件事是：

1. **Outbox 不是 Outbox** —— 现在的跨模块同步在行程事务提交之后、用进程内循环执行，
   进程中途退出会留下一个永远没人补的 `SUCCEEDED` 台账（详见 §4）。
2. **SSE 不能续传** —— 没有 `Last-Event-ID`，断线后前端只能整段重来（详见 §5）。

如果照原清单原样排期，会在已经完成的工作上重复投入，同时把这两件真正的事埋在第三/第二阶段。

## 2. 第一阶段（P0）逐项核对

| 提案项 | 实际状态 | 证据 | 还缺什么 |
| --- | --- | --- | --- |
| 完整持久化状态机 | **已完成主体** | `agent/AgentEngine.java`（RECEIVE→UNDERSTAND→PLAN→EXECUTE→REFLECT→PRESENT，REPLAN≤2，取消边界）；`config/DataSourceConfig` 启动时 `ddl-auto=validate`，缺列直接起不来 | 状态只是内存枚举，**没有落库**：进程崩溃后无法回答「这个 run 走到哪了」 |
| PendingPlan 生命周期与崩溃恢复 | **已完成主体** | `agent/PendingPlanStore.java` + `pending_plan` 表（V6/V7）；30 分钟 TTL；`claim()` 用一条条件 UPDATE `PENDING→CONFIRMING` 抢所有权；提交失败 `release()` 回 `PENDING` 让用户用同一 planId 重试 | 崩溃窗口：进程在「抢到所有权」与「提交成功」之间退出，行会**永久卡在 `CONFIRMING`**——`claim` 只认 `PENDING`，`release` 只认 `CONFIRMING` 且没有调用者，用户既不能确认也不能取消该方案，只能等 TTL 过期重来。缺一个改期的租约清扫任务 |
| Confirm 并发及事务安全 | **已完成，且超出提案范围** | `PlanConfirmationService.confirm()`：claim → 内容指纹（FR-08）→ `planRevision` 版本校验（行程被别人改过即失效）→ 审核门（FR-06）→ commit；`ConcurrentConfirmProbeTest` 断言并发确认只产生 1 个行程、条目只落库一遍 | 无 |
| Outbox 跨模块一致性 | **部分：只有模块级幂等，没有真正的 Outbox** | `service/PlanExecutionService.java` 用 `plan_id` 唯一 + `schedule_item.source_key` 唯一 + `retry(executionId)` 实现了「失败模块可单独重试、成功模块不重复创建」 | 见 §4。它是进程内尽力而为 + 人工重试，不是「事务性写入待办 + 可靠投递」 |
| SSE 断线恢复 | **未完成** | `SseDisconnectTest` / `SseStreamLifecycleTest` 只覆盖「客户端断开后服务端停止工作」 | 没有事件 ID、没有 `Last-Event-ID`、没有事件缓冲，断线即丢失全部已推送事件 |

## 3. 第二～四阶段里，提案写「没做」但其实已经有的

这些项不要再按「未实现」排期；要做的是**补齐缺口**，不是重写。

| 提案项 | 实际已有 |
| --- | --- |
| 多维 AgentBudget | `agent/AgentBudget.java` 已有端到端 deadline（默认 30s）+ 阶段预算 + 工具/模型超时 + `budget_degraded` 事件 + 指标。缺的是统一口径的**花费类**预算（token/工具调用次数/$），目前散落在各处常量 |
| MCP 统一治理层 | `mcp/McpToolBridge` 把外部工具统一注册进 `ToolRegistry`，与内置工具走同一 `ToolInvoker` + `SafetyInterceptor` + 三级风险。缺的是：多 server 的策略配置、逐 server 熔断与配额、工具白名单 |
| 旅行数据真实性校验 | `tool/ToolInvoker` 参数 schema 校验；`ReflectAgent` 校验天气/路线/预算；`source=amap\|estimated` 来源标注；天气拿不到即明确拒绝（业务码 503）而不是编造 |
| Review 风险规则扩展 | `ReflectAgent` + `PlanInspector` + `PlanReviewLoop` 已经做 HIGH/MEDIUM 分级、阻塞门（`acknowledgeIssues`）和有限修复 |
| 局部重规划 | 行中重规划只改 `PENDING` 条目，已开始/完成/跳过不被覆盖 |
| Undo 补偿机制 | `OperationHistoryService` 的 before/after 快照 + `undoLatest` + `AgentCommitUndoTest` |
| 故障恢复与重试机制 | `DelegatingLlmClient` 统一降级；`LlmResilienceTest`、`AmapResilienceTest`（10021 退避）、`RedisResilienceTest`、`S3ResilienceTest`、`EmbeddingResilienceTest` 覆盖各外部依赖失败 |
| 自动化测试和 CI 门禁 | `.github/workflows/ci.yml` + `codeql.yml` + `visual-baseline.yml`；`quality-gates.md`、`ci.md` 有成文门禁；后端 639 tests / 前端 537 tests |
| 实时天气与交通事件 | `WeatherService` 接 **Open-Meteo 真实预报**（失败负缓存，不模拟）；交通是 `RouteService` 的路线耗时，不是实时路况事件流 |
| 旅行足迹与消费分析 | `TripReviewService`（完成率、计划 vs 实际、AI 总结）+ 足迹地图（`visitedAt` 非空才算去过） |
| 用户旅行偏好记忆 | `ConversationService` 最近 N 条会话记忆 + `RequirementSlots` 槽位追问；**没有**跨旅行的长期偏好画像 |

## 4. 真正的问题：Outbox 是「事后尽力而为」，不是 Outbox

`PlanConfirmationService.confirm()` 的顺序是：

```
pendingPlans.remove(planId)          ← 方案行删除，确认已完成
→ 执行 PlanExecutionService.syncConfirmedPlan(...)   ← 单独的 @Transactional
```

`syncConfirmedPlan` 内部逐模块 `try/catch`，模块异常被吞成 `FAILED` 字段写进台账。
这套设计能防「重复创建」，但防不住**过程丢失**：

1. **崩溃丢失**：进程在 commit 成功、同步未跑完时退出 → 行程已落库，`PlanExecution` 行要么不存在，
   要么因模块异常未写入就被整条事务回滚 → 用户看到「已保存」但日程/预算/足迹永远缺失，
   而且**没有任何自动补偿**，只能靠用户自己发现并手动重试。
2. **误报**：模块抛异常时进程仍在，`execution.setStatus()` 把整体标成 `SUCCEEDED` 的路径存在；
   只要写入台账成功，系统就认为「已处理」，不会再有第二次尝试。
3. **无投递保障**：所谓「Outbox」应当与行程提交**同一事务**写入待发事件，再由独立消费者
   至少一次投递 + 幂等消费。当前实现两者不在同一事务，也没有消费者。

**建议的最小改造**（不引入消息中间件）：在同一个 MySQL 事务里
`INSERT plan_outbox(plan_id, module, payload, status='PENDING')` 与行程变更一起提交；
由 `@Scheduled` 清扫任务按 `status='PENDING' AND next_attempt_at <= now()` 领取、
（沿用现有 `source_key` 幂等键）投递、成功置 `DONE`、失败累计 `attempts` 并指数退避。

## 5. 真正的问题：SSE 断线后无法续传

提案的验收用例 4「规划中断开连接后不能继续产生无意义调用」**已经有了**
（`SseDisconnectTest` + 取消边界）。但如果断线发生在**推送阶段**，用户丢掉的是已生成的内容，
只能整段重新规划——恰恰是「无意义调用」的另一面。

当前 `AgentEvent` 无事件 ID，客户端无法告诉服务端「我收到第 N 条」。最小改造：
事件加单调序号 → 响应带 `id:` 行 → 服务端在 run 内保留有界环形缓冲（如最近 200 条）
→ 重连带 `Last-Event-ID` 时先补发缓冲内的事件再续流。超窗则返回明确的「请重新发起」业务码，
而不是静默重放或重复落库。

## 6. 提案的 9 类测试：实际覆盖情况

| 测试类型 | 提案场景 | 现有覆盖 | 结论 |
| --- | --- | --- | --- |
| 单元 | 意图解析、预算、风险规则 | `MessageParser*Test`(4)、`AgentBudget*Test`(3)、`ReflectAgent*Test`(2)、`IntentAgent*Test`(2) | ✅ 已覆盖 |
| 集成 | MCP、RAG、数据库 | `McpBridgeTest`、`RagEvaluationTest` 等 10+、`H2FlywayMigrationTest`、`MySqlFlywayMigrationTest` | ✅ 已覆盖（真实 MCP/MySQL 用例按设计跳过） |
| 并发 | 两个请求同时确认 | `ConcurrentConfirmProbeTest`、`ConcurrentCommitHistoryTest`、`OptimisticLockConflictTest`、`LlmQuotaConcurrencyTest` | ✅ 已覆盖 |
| 一致性 | 提交成功但预算同步失败 | `PlanExecutionSyncTest`、`PlanScheduleIdempotencyTest` | ⚠️ 覆盖「同步失败可重试」，**未覆盖崩溃丢失** |
| 故障 | LLM/MCP/DB 超时与断开 | `LlmResilienceTest`、`AmapResilienceTest`、`RedisResilienceTest`、`S3ResilienceTest` | ✅ 已覆盖（DB 断开缺） |
| 恢复 | 重启后恢复待确认方案 | `PendingPlanStoreTest`、`PendingPlanApiTest` | ⚠️ 覆盖「重启后方案还在」，**未覆盖 `CONFIRMING` 卡死** |
| 安全 | 越权确认、重复提交、篡改方案 | `TripOwnershipTest`、`PendingPlanRevisionTest`（双指纹）、`AccessTicketTest`、`TicketReadOnlyStreamTest` | ✅ 已覆盖 |
| SSE | 断线重连、事件重复、事件丢失 | `SseDisconnectTest`、`SseStreamLifecycleTest` | ❌ **只有断线停止，没有重连/重复/丢失** |
| 回归 | 规划、修改、记账、日程 | 148 个测试类中的主体 | ✅ 已覆盖 |

## 7. 五个关键验收用例：现状判定

| # | 用例 | 当前能否通过 | 依据 |
| --- | --- | --- | --- |
| 1 | 连续点两次确认，只产生一次有效提交 | **能** | `claim()` 条件更新 + `ConcurrentConfirmProbeTest` 已断言只建 1 个行程 |
| 2 | 方案看到后被别端修改，旧方案确认必须失败 | **能** | `planRevision` 版本校验，不一致即 `remove` 并返回冲突 |
| 3 | 提交成功但预算同步失败，系统能独立重试 | **部分** | `PlanExecutionService.retry()` 支持按模块重试；但进程崩溃时台账可能根本没落库，无法重试 |
| 4 | 规划中断开连接，后续阶段不再产生无意义调用 | **能** | 取消边界 + `SseDisconnectTest` |
| 5 | LLM 完全不可用时，规则规划器仍产出合法可审核方案 | **能** | `PlannerAgent` 回落规则规划器 + `PlannerFallbackMessageTest`；E2E 隔离后端即 LLM 全关 |

5 个里有 3 个已通过、1 个部分、1 个已通过。**用这组用例当「下一阶段目标」会重复投入。**
建议把它们固化成 `release-acceptance` 套件（5 条断言跑一次），并把力气放到 §4/§5。

## 8. 建议的修正后优先级

| 顺序 | 事项 | 理由 | 规模 |
| --- | --- | --- | --- |
| 1 | `CONFIRMING` 卡死清扫（租约 + 改期退还） | 唯一会让用户**无法操作**的现存缺陷 | S |
| 2 | 真 Outbox（同事务写入 + 定时投递 + 退避） | 唯一会**静默丢数据**的现存缺陷 | M |
| 3 | SSE 事件 ID + `Last-Event-ID` 续传 | 断线重来直接放大 LLM 成本，也是提案「无意义调用」的另一半 | M |
| 4 | Run 状态落库（状态机快照） | 让崩溃后能回答「走到哪、能不能续」 | M |
| 5 | 5 条验收用例固化为发布门禁 | 防止以上改造回归 | S |
| 6 | 统一花费预算口径（token/调用次数/$） | 在已有 deadline 预算上叠加，不是重写 | M |
| 7 | MCP 逐 server 熔断与白名单 | 多 server 部署前的必要治理 | M |
| 8 | OpenTelemetry（当前零依赖） | 已有 Micrometer 指标 + traceId，缺跨服务链路 | M |

前三项是「可信执行系统」这个壁垒的真实缺口；第六、七项是「可控 Agent 系统」的真实缺口；
「真实旅行服务系统」的天气/景点/酒店/交通/预算/足迹**已经真实化**，剩下的主要是
真实 Embedding provider 与流量成本，不属于 v1 阻断项。

## 9. 核对方法说明

- 逐文件阅读：`PendingPlanStore`、`PendingPlanRepository`、`PlanConfirmationService`、
  `PlanExecutionService`、`AgentEngine`、`docs/wiki/03-agent-engine.md`、`docs/PROJECT_ROADMAP.md`。
- 测试类清单来自 `travel-agent-server/src/test` 实际文件（148 个），不是文档转述。
- 迁移清单来自 `src/main/resources/db/migration*` 实际文件（V1–V24）。
- OpenTelemetry 依赖经 `pom.xml` 与 `docker-compose.yml` 全文检索确认**不存在**。
- 本文只记录代码事实；未运行完整 `mvn verify`（文档所述 639 tests 为上一轮记录，本核对未复跑）。