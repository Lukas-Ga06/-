# 第十五批共享契约（Batch 15）：FR-02 / FR-04 任务 DAG 与委派

> 状态：已落地（历史） | 日期：2026-11-18 | 说明：契约内容已进入实现，保留原文作追溯；当前事实见 [docs/guide](../guide/README.md)，落地记录见 [CHANGELOG 第十五批](../CHANGELOG.md)。

本批只做一件事：**把「固定阶段序」升级成可依赖、可委派、可回收重派的任务图**，并把
「委派 / 回收 / 冲突反馈」三类事件送进 run 追踪与 SSE 过程时间线。
承接 [v2.2-iteration.md](../guide/v2.2-iteration.md) §7。

## 0. 范围与边界

**做**：

- `AgentTask` 增加 `dependsOn`（前置任务 id 列表）、`attempt`（第几次尝试）、
  `replacesTaskId`（被重派替换的失败/冲突任务）三个字段，新增 `CONFLICT` 状态（V18 迁移，H2/MySQL 中性）。
- 新增确定性 `Coordinator`：按依赖图派发任务；前置未完成时**拒绝启动并落一条 CONFLICT 任务**；
  失败/冲突可「回收 → 重派」，重派产生新任务行、旧行保留为证据，**调用方只拿到一份最终结果**。
- 规划链路真正按图执行：`INTENT → PLAN → {PREVIEW ‖ REVIEW} → (REPLAN → REVIEW)* → COMMIT`，
  其中 `PREVIEW` 与 `REVIEW` 依赖同一 `PLAN`、互不依赖，**并行执行**；
  `REPLAN` 由 `REVIEW` 的结论委派（不再由引擎内联调用修复器）。
- SSE 新增 `delegate` / `recover` / `conflict` 三个事件（纯增量，旧前端忽略即可）。
- `GET /api/agent/runs/{runId}` 的任务节点新增 `dependsOn` / `attempt` / `replacesTaskId`（纯增量）。

**不做**（本批红线）：

- 不改确认门 / `PlanOperation` / commit / undo 语义、`PendingPlanStore` 与 TTL、预算三口径；
- 不改任何既有 REST 请求体/响应字段含义，不删字段、不改事件名；
- 不引入外部工作流引擎，**不让 LLM 当调度器**：依赖关系、并行、重派全部确定性、可测试
  （与 ADR-007「确定性自愈」同一原则）；
- `RESEARCH` 任务保持现状（在 `PLAN` 任务体内由规划器调用，作为 PLAN 的子任务，不单独声明依赖）。

## 1. 任务图（本批之后一次规划的真实形状）

```
INTENT ──▶ PLAN ──┬──▶ PREVIEW ──────────────┐
                  │                          ├──▶ PRESENT（待确认）
                  └──▶ REVIEW ──┬──passed────┘
                                │
                                └──!passed──▶ REPLAN ──▶ REVIEW（再审）
                                                  │
                                                  └── 改过计划 → PREVIEW 重派（替代首轮预览）
```

- 依赖边一律落库（`agent_task.depends_on`），一次运行的图可查询、可展示；
- `PREVIEW` 与 `REVIEW` 在同一 run 内并行：两行任务都在各自 body 开始前创建
  （保证 id 顺序稳定），body 在专用线程池并行执行，时间区间重叠；
- 任务图是**固定的确定性编排**：同输入得到同一条图，不因模型输出而变形。

## 2. 实体与迁移（V18）

```sql
ALTER TABLE agent_task ADD COLUMN depends_on VARCHAR(1000);   -- 逗号分隔的 taskId；NULL = 无前置
ALTER TABLE agent_task ADD COLUMN attempt INT NOT NULL DEFAULT 0;
ALTER TABLE agent_task ADD COLUMN replaces_task_id VARCHAR(64);
```

| 字段 | 含义 |
|---|---|
| `dependsOn` | 前置任务 id 列表（逗号分隔存储，DTO 为字符串数组）；前置必须全部 `SUCCEEDED` 才允许启动 |
| `attempt` | 第几次尝试（0 = 首次；1、2… = 重派）。与 `REPLAN` 的「第 N 次重规划」语义一致 |
| `replacesTaskId` | 本次任务替换掉的任务 id（失败/冲突/旧版本）；首次为 NULL |
| `status` | 新增 `CONFLICT`：前置未完成、任务被拒绝启动 |

`AgentTask.STATUS_CONFLICT = "CONFLICT"`。既有 `RUNNING / SUCCEEDED / FAILED` 语义不变。

## 3. Coordinator 语义（确定性）

```java
Coordinator.TaskHandle dispatch(ctx, new TaskSpec(receiver, taskType, List<TaskHandle> dependsOn),
                                () -> body, summarizer);
Coordinator.TaskHandle redispatch(ctx, new TaskSpec(receiver, taskType, deps, replaces, attempt),
                                  () -> body, summarizer);
List<Dispatch> dispatchAll(ctx, List<Dispatch>);   // 依赖已满足的并行组
```

1. **前置检查**：`dispatch` 先校验 `dependsOn` 中每个任务存在且 `SUCCEEDED`；
   不满足 → 落一条 `CONFLICT` 任务（summary 写明缺失前置）→ 发 `conflict` 事件 → 抛
   `Coordinator.TaskConflictException`，**body 不执行**。
2. **派发**：前置满足 → 落 `RUNNING` 任务（带依赖边/attempt/replaces）→ 发 `delegate` 事件
   （`{taskId, receiver, taskType, dependsOn, attempt}`）→ 执行 body → 落 `SUCCEEDED`/`FAILED`。
3. **重派**：`redispatch` 总是产生**新任务行**（`attempt+1`、`replacesTaskId=旧行`），发 `recover`
   事件（`{taskId, replacesTaskId, attempt, reason}`）；旧行状态保持失败/冲突原值。
   调用方只使用新行的结果 —— 同一逻辑工作只有一份最终结果。
4. **并行组**：`dispatchAll` 先按声明顺序创建全部任务行（id 顺序稳定、便于回查），
   再把 body 并行跑在 `agent-dag` 线程池，全部结束后按声明顺序返回；任一分支异常先取消
   其余分支并原样抛出（不吞异常）。
5. **降级**：`ctx.getRunId() == null`（追踪不可用）时全部方法退化为直接执行，行为与升级前一致。

## 4. SSE 事件（新增，纯增量）

| 事件 | data | 时机 |
|---|---|---|
| `delegate` | `{taskId, receiver, taskType, dependsOn[], attempt}` | 每次任务真正启动前 |
| `recover` | `{taskId, receiver, taskType, replacesTaskId, attempt, reason}` | 重派（替换旧任务）时 |
| `conflict` | `{receiver, taskType, missingDeps[], reason}` | 前置未完成、任务被拒绝启动时 |

既有事件（`start/run/intent/planning/tool_call/tool_result/rag_result/replan/plan_update/focus/confirmation/complete/error`）
名称、负载、顺序语义一律不变；`replan` 事件仍按「第 N 次重规划」发。

## 5. 查询契约（新增字段）

```jsonc
// GET /api/agent/runs/{runId} → data.tasks[i]
{
  "taskId": "…", "receiver": "planner-agent", "taskType": "PLAN",
  "status": "SUCCEEDED",
  "dependsOn": ["<intent-task-id>"],       // 新增；无前置为 []
  "attempt": 0,                             // 新增
  "replacesTaskId": null,                   // 新增
  "summary": "生成 16 项操作", "error": null,
  "startedAt": "2026-11-18T21:30:00", "endedAt": "…", "durationMs": 812
}
```

- 旧字段一个不改；新增字段缺省值分别为 `[] / 0 / null`（旧数据行自动退化为该形状）。

## 6. 验证口径

- 后端：`mvn clean test` 全绿；新增用例覆盖
  「依赖边落库且可查」「前置未完成 → CONFLICT + 拒绝启动」「冲突后重派成功且只有一份结果」
  「PREVIEW 与 REVIEW 时间区间重叠」「REPLAN 依赖 REVIEW 且第二次重规划为 replaces 重派」
  「旧数据（无依赖列）行为不变」。
- 前端：`npm run typecheck && npm test && npm run build` 全绿；过程时间线渲染
  「委派 / 重派 / 冲突」三类事件，运行图节点展示依赖与被替换关系。
- 真实 HTTP 冒烟：一次规划的任务图依赖边齐全、并行任务时间重叠、SSE 可见 delegate/recover；
  确认提交链路（含 contentHash / ack）行为与第十三、十四批完全一致。