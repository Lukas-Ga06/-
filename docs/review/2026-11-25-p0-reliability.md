# P0 可靠执行链路评审记录（2026-11-25）

> 快照日期：2026-11-25 ｜ 验证范围：`feat/p0-reliability` 的确认租约/提交栅栏、事务性 Outbox、运行状态落库与崩溃恢复、SSE 事件持久化/断线续传、前端自动续传、发布验收门禁 ｜ 环境：Windows + MSYS2 / JDK 17 / Maven 3.9.16 / H2 内存测试上下文（`ReleaseAcceptanceTest` 关闭 LLM）；命令由 lead 在本机执行 ｜ 结论：**后端全量 `mvn -B verify`、前端全部门禁（除 e2e）与隔离浏览器 E2E 均已通过**；真实 MySQL 迁移、真实网络断线与多实例行为仍未验证，发布判断以 CI 与真实环境验收为准。

## 1. 范围

本快照对应分支 `feat/p0-reliability` 的工作区内容。后端两次合并提交（`d6f6071` 确认链路、`a19d155` 运行状态与 SSE）
已在历史中；前端 `travel-agent-web/src/api/agent.ts` 与 `agent.test.ts` 的续传改动与本文档在同一工作区交付。

| 工作流 | 落地内容 | 主要位置 |
| --- | --- | --- |
| 确认可靠性 | 租约令牌 + 过期清扫 + 单事务提交栅栏 | `agent/PendingPlanStore.java`、`agent/PlanConfirmationService.java`、`service/ConfirmCommitTx.java`、`service/PendingPlanRecoveryJob.java`、`V25__pending_plan_claim_lease.sql` |
| 跨模块一致性 | 事务性 Outbox、条件领取、指数退避、租约恢复、迟到补建 | `service/PlanOutboxService.java`、`service/PlanOutboxDispatcher.java`、`service/PlanExecutionService.java`、`entity/PlanOutbox.java`、`V26__plan_outbox.sql` |
| 运行状态 | `agent_run.stage/heartbeat_at/request_id`、阶段历史、心跳超时置 `ABANDONED` | `service/AgentTraceService.java`、`service/AgentRunRecoveryJob.java`、`V27__agent_run_state.sql` |
| SSE 续传 | 事件持久化、帧 `id`、`Last-Event-ID`、独立续传端点、`resume_timeout`、合成 `complete` | `agent/AgentContext.java`、`service/AgentRunEventService.java`、`controller/AgentController.java`、`V28__agent_run_event.sql` |
| 前端 | 断线后最多自动续传一次、按 `seq` 去重、终态回调只一次 | `travel-agent-web/src/api/agent.ts`、`agent.test.ts` |
| 发布门禁 | `ReleaseAcceptanceTest`（`@Tag("release-acceptance")`）+ CI 命名步骤 | `src/test/java/com/travel/agent/release/ReleaseAcceptanceTest.java`、`.github/workflows/ci.yml` |

## 2. 关键决策与不变量

### 2.1 确认：租约只决定「谁有权提交」，栅栏决定「旧租约不能提交」

- `claim` 用一条条件 UPDATE 把 `PENDING` 改成 `CONFIRMING`，同时写入 UUID `claim_token` 与 `claimed_at`；
  并发确认同一方案时只有一方拿到令牌，另一方返回业务失败，不产生 500。
- 提交走 `ConfirmCommitTx` 的**单事务**：`executorAgent.commit` + 3 条 `plan_outbox(PENDING)` +
  `deleteClaimed(planId, token)` 栅栏。栅栏必须影响 1 行，否则抛 `LeaseLostException` 整体回滚。
  这保证「被清扫/被重新抢占的旧租约」不可能在失效后再落库一次。
- 租约失效时调用方按令牌 `release`（不匹配是空操作）并返回 `status=FAILED`；
  普通提交失败同样 release 并保留方案行，用户可用同一 `planId` 重试。
- `PendingPlanRecoveryJob` 启动时清扫一次，之后默认每 30 秒把 `claimed_at` 超过
  `travel.pending-plan.lease-minutes`（默认 5 分钟）的 `CONFIRMING` 改回 `PENDING`。

### 2.2 Outbox：至少一次投递 + 幂等消费

- 3 个模块 `calendar` / `budget` / `footprint` 与行程变更同事务入队；`(plan_id, module)` 唯一键防止重复提交翻倍。
- 投递器按 `status=PENDING AND next_attempt_at<=now` 条件领取；成功置 `DONE`；
  失败 `attempts+1`、写 `last_error`、按 `10s × 2^(n-1)`（封顶 1h）退避；默认第 8 次失败置 `DEAD`。
- `DISPATCHING` 领取超过默认 10 分钟视为执行者崩溃，改回可投递并消耗一次 attempt；
  次数耗尽直接 `DEAD`，避免「恢复 → 崩溃」无限循环。
- 确认请求内的同步投递与定时 dispatcher 互斥靠条件领取；进程在提交后、同步前崩溃时，
  dispatcher 会按 Outbox 补建台账与日程，成功模块不重复创建。
- `cleanupForTrip` 删除该行程的 Outbox 行；人工 `retry` 只重跑 `FAILED` 模块，并同步 Outbox 状态。

### 2.3 运行状态：崩溃后要能回答「走到哪了」

- `AgentEngine.handle` 在**第一个 SSE 事件之前**调用 `startRun(..., requestId)`，run 创建即写
  `RUNNING` / `RECEIVE` / `heartbeat_at`；此后每次 `setState` 更新 `agent_run.stage` + `heartbeat_at`
  并追加 `agent_run_stage`（run 内 `seq` 单调，唯一键 `(run_id, seq_no)`）。
- `agent_run.status` 含 `ABANDONED`：心跳超时或进程重启后由 `AgentRunRecoveryJob` 判定，
  与执行体自报的 `FAILED` 区分；恢复同时补一条 `stage=ABANDONED` 历史。
- `request_id` 用于写操作幂等与按 `(user_id, request_id)` 回查；**SSE 续传实际认帧 id 里的 `runId`**，
  不使用 `request_id`。实体注释里「重连据此找到原 run」的表述与当前控制面实现不一致，以控制面为准。

### 2.4 SSE：先落库，再下发；断线只补缺口

- `AgentContext#emit` 在 run 内分配从 1 开始的 `seq`，方法加锁保证「落库 + 下发」不乱序。
- `AgentRunEventService` 用 `REQUIRES_NEW` 先落 `agent_run_event` 再实时下发；写库失败只记 DEBUG，
  不影响主流程。单条载荷超过约 1,000,000 字符截断并 WARN。
- 帧 id 固定 `<runId>:<seq>`。GET/POST `/api/agent/stream` 收到 `Last-Event-ID` 即续传，
  格式非法 400，归属/存在性不符 404，**绝不静默重新规划**。
- `GET /api/agent/runs/{runId}/events?lastEventId=N`（`@Hidden`）先补发 `seq>N` 的事件；
  终态后关闭，缺 `complete`/`cancelled` 时合成 `complete(status=FAILED)`；
  仍 `RUNNING` 时每 500ms 轮询、最长 120s，超时发 `resume_timeout`；线程池满 503。
- 续传连接断开不会取消原始运行；取消语义仍只属于 `/api/agent/stream` 的 `StreamLifecycle`。

### 2.5 前端：最多一次自动续传，回调只一次

- 记录每帧 id（`runId` + `lastSeq`）；连接层中断时显式 `close()` 原 `EventSource`，
  用同一份票据向续传端点发起一次 GET；补发事件与实时事件走同一 `dispatchFrame`。
- 按 `seq` 去重；`complete` / `cancelled` / `onError` 全局只回调一次；续传失败或
  `resume_timeout` 只报一次错，不无限重试；POST/fetch 传输手工解析 `id:` 行，断流后同样转 GET 续传。
- 续传期间保持 streaming 状态，不提前解锁输入框。

## 3. 验证命令与结果

| 验证 | 命令 | 结果 | 说明 |
| --- | --- | --- | --- |
| 发布验收套件 | `cd travel-agent-server && mvn -B -Dtest=ReleaseAcceptanceTest -DfailIfNoTests=false test` | **5/5 通过** | 覆盖双确认只提交一次、旧方案必失败、提交后崩溃 Outbox 补投、断线停止无意义调用、LLM 不可用仍产出可审核方案 |
| 合并后定向回归 | `PendingPlanLease` / `ConfirmFence` / `PlanOutbox*` / `AgentRun*` / `Sse*` / `PlanExecutionSync` / `ConcurrentConfirmProbe` / `AccountDeletion` / `H2FlywayMigration` 等 | **69 tests 全绿** | 覆盖确认租约/栅栏、Outbox、运行状态、SSE、账号注销、H2 Flyway 全链与并发确认 |
| 后端完整门禁 | `cd travel-agent-server && mvn -B verify` | **BUILD SUCCESS（2:22）**：Tests run **684**、Failures **0**、Errors **0**、Skipped **3**；JaCoCo check 通过；Checkstyle 0 violations；SpotBugs BugInstance 0 / Error 0 | 3 个 skipped 为 2 个真实 MCP 用例 + 1 个真实 MySQL 迁移用例（按设计跳过） |
| 前端类型检查 | `cd travel-agent-web && npm run typecheck` | 通过 | 与 lint/format/coverage/build/api 一起作为完整前端门禁 |
| 前端 lint / format | `npm run lint` / `npm run format:check` | lint **0 errors / 47 既有 warnings**；format 全部通过 | warnings 为既有基线，不在本次新增 |
| 前端覆盖率测试 | `npm run test:coverage` | 通过：**53 files / 552 tests**，覆盖率门槛通过 | 数字为本次快照证据，不写入 README 等长期文档 |
| 前端构建与契约 | `npm run build:check` / `npm run api:check` | 包体预算全部通过；生成类型与 OpenAPI 快照无漂移 | — |
| 隔离浏览器 E2E | `cd travel-agent-web && npm run e2e:isolated` | **14/14 passed** | 含 `planner-form` 两条 POST SSE 流式规划 → 确认 → 建行程 |
| 文档校验 | `node scripts/docs-check.mjs` | **通过（exit 0）**：91 个 Markdown、836 条相对链接、436 条命令（npm 288 / mvn 148）；0 断链、0 失效命令，1 条白名单外 Maven goal 警告（历史文档 `dependency-check`，与本改动无关） | 本次执行 |
| 真实 MySQL 迁移 | CI `mysql-migration` job / `scripts/mysql-it.ps1` | **未在本次执行** | 本机 Docker daemon 未运行、3307 无实例；V25–V28 的 MySQL 全链以 CI 为准 |
| CI 命名步骤 | `.github/workflows/ci.yml` → `Release acceptance (P0 reliability)` | 已接入 | 在 `mvn verify` 之后单独跑同一套件 |

> 本表里的 684 tests / 552 tests / 14 条 E2E 是**本次快照的验证证据**，不是长期承诺；日常门禁仍以 CI 输出为准，README 等长期文档不维护静态用例数。

## 4. 覆盖边界与残余风险

1. **本地全量门禁已通过，但发布仍以 CI 为准**：本次 `mvn -B verify`、前端全部门禁与隔离 E2E 都已通过；
   这证明当前工作区的确定性用例与门禁成立，不等于 CI 环境（Linux runner、service container、缓存冷启动）同样通过，
   也不等于真实外部依赖可用。发布前仍必须等 CI `backend` 与 `mysql-migration` 两个 job 绿。
2. **发布验收自身不经过 Flyway**：`ReleaseAcceptanceTest` 跑在测试配置的 H2 内存库上，
   `src/test/resources/application-test.yml` 明确 `jpa.hibernate.ddl-auto=create-drop` 且
   `spring.flyway.enabled=false`。因此这 5 条验收验证的是实体/业务语义，**不验证 V25–V28 迁移脚本**。
   本次定向回归中的 `H2FlywayMigrationTest` 会在全新 H2 库上执行默认 profile 的全链（含 V25–V28），
   但它只断言 V22/V23 的唯一约束，**没有对 V25–V28 的新列/新表做专项结构断言**；MySQL 侧仍依赖 CI job。
3. **真实 MySQL 未跑**：V25–V28 均为双方言中性 SQL（`ALTER TABLE ADD COLUMN` / `CREATE TABLE IF NOT EXISTS` /
   普通 `CREATE INDEX`），但本次没有在 MySQL 8.4 上执行全链，也没有做 `V24→V28` 增量升级演练；
   本机 Docker daemon 未运行、3307 无实例，以 CI `mysql-migration` 输出为准。
4. **确认同步与定时投递的竞争窗口**：确认请求内的同步投递与 `PlanOutboxDispatcher` 靠条件领取互斥，
   不会重复写业务数据；但若 dispatcher 先领走全部到期行、台账尚未写入，确认响应里的
   `execution` 可能为空或只有部分模块。数据最终仍会由 dispatcher 补齐；客户端不应把「这次响应没有
   `execution`」当成同步丢失，应通过执行台账/运行详情回查。
5. **多实例阶段历史可能丢一行证据**：`agent_run_stage.seq` 用「当前 max+1 + 唯一键重试（最多 5 次）」，
   超过 5 个并发阶段推进时最后一行可能写失败（静默 DEBUG），`agent_run.stage` 快照仍然更新。
   这是**证据缺失**，不是业务数据丢失；当前没有做双实例压测。
6. **大事件截断**：单条 SSE 事件超过约 1MB 时写入侧截断，续传补发按文本兜底，客户端不能假设所有补发事件
   都是完整 JSON；完整方案/结果仍以 `complete` 事件或运行详情为准。该路径没有自动化测试。
7. **`resume_timeout` 未做后端自动化**：续传循环的 120s 真实等待路径没有单测；前端对收到
   `resume_timeout` 后终止的处理有单测。真实网络切换（代理/断网/移动网络）未跑。
8. **租约清扫的等待上限**：租约 5 分钟 + 清扫间隔 30 秒，崩溃后用户最多约 5.5 分钟可重新确认；
   若清扫与提交恰好竞争，提交方拿到 `LeaseLostException` 并返回业务失败，不会部分落库。
9. **Outbox `DEAD` 需要人工**：重试耗尽后没有自动告警（当前只能靠日志「Outbox 重试耗尽，置 DEAD」或查库）；
   建议后续补低基数指标与告警（`outbox.status{status}` / `agent.run.abandoned` / `pending.plan.swept`），在此之前用日志告警兜底。
10. **未覆盖的端到端项**：真实多实例部署、真实 SSE 断线重连、真实 MQ/消息中间件（本方案刻意不引入）、
    以及真实发布环境下的长时压测。

## 5. 结论

在**本次已执行的验证**范围内，P0 第一阶段的五条承诺（单次提交、旧方案不可提交、崩溃后同步可补、
断线不重复调用、LLM 不可用仍可规划）成立；后端全量 `mvn verify`（684 tests、3 skipped）、
前端 typecheck/lint/format/coverage/build/api 全部门禁与隔离 E2E 14/14 均通过。
但这份记录不是发布保证：真实 MySQL 迁移、真实网络断线续传、多实例行为与真实发布环境的长时压测
仍未验证，发布判断必须以 CI 输出和真实环境验收为准，不能用本文的 5/5、69/69 或 684 代替。