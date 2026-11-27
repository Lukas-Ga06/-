# 运行状态映射（RunState 七态）

> 状态：当前事实 | 适用版本：0.1.0 | 最后验证：2026-11-27 | 事实来源：`travel-agent-web/src/lib/runState.ts`、`travel-agent-web/src/lib/resource.ts`、`travel-agent-web/src/api/agent.ts`、`travel-agent-web/src/types/agent.ts`、`travel-agent-server/src/main/java/com/travel/agent/entity/AgentRun.java`

本文件是「统一运行反馈」的**唯一映射表**：把后端运行状态、前端资源态与 SSE 事件名收敛到同一套 `RunState` 七态，
并给出用户文案、下一步操作提示与语义色档位。实现只有一份 —— `travel-agent-web/src/lib/runState.ts`；
**改来源取值、改映射或改文案时，代码、单测、本表三处同批更新**。

## 1. 七态与文案

| `RunState` | 用户文案 | 语义 | 下一步提示（`hint`） | `tone` |
| --- | --- | --- | --- | --- |
| `loading` | 进行中 | 本轮仍在执行或等待下一步 | 本轮仍在执行；可展开过程查看进度，长时间没有变化再重试。 | `info` |
| `empty` | 暂无内容 | 没有可展示的内容（尚未开始或确实为空） | 还没有可展示的结果；发起一次对话或操作后这里会出现。 | `muted` |
| `degraded` | 已降级 | 有环节被跳过、结果可能不完整或未知 | 部分能力被跳过或结果可能不完整；可查看运行详情，必要时重试。 | `warn` |
| `partial` | 部分成功 | 正常收尾但有部分未完成 | 有些部分没有完成；先查看运行详情，确认缺了什么再决定是否重试。 | `warn` |
| `failed` | 失败 | 本轮没有完成 | 本轮没有完成；可以重试，反复失败时打开运行记录或系统诊断。 | `danger` |
| `cancelled` | 已取消 | 本轮被用户或连接断开终止 | 本轮已停止，没有继续执行；需要时重新发起即可。 | `muted` |
| `complete` | 完成 | 正常收尾 | 本轮已正常收尾；可以查看结果或继续下一步。 | `ok` |

`tone` 是语义色档位（`ok` / `warn` / `danger` / `muted` / `info`），**不是色值**；具体颜色由
`src/styles/tokens.css` 与 `components/RunStatus` 决定。七态口径与 `components/RunStatus` 的局部
`RunState` 一致。

**`unknown` 不属于七态。** 它是映射函数对未登记取值的显式兜底结果：

```ts
{ state: 'unknown', source: 'agent-run' | 'resource' | 'sse', raw: string }
```

unknown 保留来源与原始值用于排查，**不抛异常、不静默归到 `complete`**；UI 必须把 unknown 显示成
「状态未知」，不能当成功。`RUN_STATE_META.unknown` 也提供了兜底文案与 `muted` 档位。

## 2. 后端运行状态 → RunState

来源：`travel-agent-server/src/main/java/com/travel/agent/entity/AgentRun.java`
（`STATUS_*` 常量，`:38-51`）。后端实际有 **6** 个取值：

| `AgentRun.status` | `RunState` | 说明 |
| --- | --- | --- |
| `RUNNING` | `loading` | 执行中或等待下一步 |
| `SUCCEEDED` | `complete` | 正常收尾 |
| `PARTIAL_SUCCESS` | `partial` | 正常收尾，但有阶段失败/降级、过程警告或预算降级；P0-07 明确不能与 `SUCCEEDED` 共用绿标 |
| `FAILED` | `failed` | 执行体自报失败 |
| `CANCELLED` | `cancelled` | 客户端断开或取消 |
| `ABANDONED` | `degraded` | 心跳超时/进程重启，执行体已不存在、**结果未知**；后端刻意与 `FAILED` 区分。映射保留「结果可能不完整」语义，不显示为成功 |

> **类型缺口（现状）**：前端 `src/types/agent.ts` 的 `AgentRunStatus` 只有前 5 个值，**漏了
> `ABANDONED`**。映射层按后端事实补上；`types/agent.ts`、`Workbench` 与 `AgentRunsDrawer` 目前会把
> `ABANDONED` 落进各自的 `default`（显示成「已取消」），属于错误文案，统一替换不在本映射层处理。

## 3. 前端资源态 → RunState

来源：`travel-agent-web/src/lib/resource.ts`（`ResourceState`，`:9-15`）。实际有 **6** 个 `status`
（不是 7 个）：

| `ResourceState.status` | `RunState` | 说明 |
| --- | --- | --- |
| `idle` | `empty` | 尚未发起加载，没有内容可展示 |
| `loading` | `loading` | 请求在途；带 `previous` 时仍按进行中 |
| `success` | `complete` | 拿到了数据 |
| `empty` | `empty` | 请求成功但确实没有数据 |
| `error` | `failed` | 加载失败；是否给「重试」入口由 `retryable` 决定 |
| `offline-stale` | `degraded` | 离线缓存，内容可能过期，不能当完整成功 |

## 4. SSE 事件名 → RunState

来源：`travel-agent-web/src/api/agent.ts` 的 `EVENT_NAMES`（`:82-104`，17 个）加后端实际会发、
但 GET 通道未注册的事件，共 **19** 个取值：

| 事件 | `RunState` | 通道 / 说明 |
| --- | --- | --- |
| `run` | `loading` | 连接后下发 `runId` |
| `start` | `loading` | 会话开始 |
| `intent` | `loading` | 意图识别完成 |
| `planning` | `loading` | 进入规划/重规划 |
| `replan` | `loading` | 审核未通过，进入有限重规划 |
| `delegate` | `loading` | 任务委派 |
| `recover` | `loading` | 回收重派 |
| `conflict` | `degraded` | 前置未完成、任务被拒后回收；本轮继续，但过程已出现缺口 |
| `tool_call` | `loading` | 工具调用开始 |
| `tool_result` | `loading` | 工具调用返回 |
| `rag_result` | `loading` | 知识库检索返回 |
| `plan_update` | `loading` | 方案预览产出 |
| `focus` | `loading` | 定位事件；本轮仍在进行 |
| `confirmation` | `loading` | 等待用户确认；七态没有独立「待确认」档，按进行中处理 |
| `budget_degraded` | `degraded` | 预算耗尽跳过阶段（`AgentContext.recordBudgetDegradation:161`）。**GET 的 `EVENT_NAMES` 未注册它**，GET 通道会静默丢；POST 逐帧解析能收到 |
| `complete` | `complete` | 本轮收尾。若随后结果卡显示 `FAILED` / `NEED_CONFIRMATION`，以结果卡为准 |
| `cancelled` | `cancelled` | 前端已注册、后端保留 `EVENT_CANCELLED` 常量；当前主链路的断开走 `AgentRun.status=CANCELLED`，未 emit 该事件；用户主动停止由 `agentStore.abort()` 合成活动行 |
| `error` | `degraded` | **过程警告**：服务端随后仍会下发 `complete`，不能当本轮失败 |
| `resume_timeout` | `degraded` | 仅续传端点：等待 120s 仍未终态时下发并关闭连接；服务端 run 可能仍在执行 |

> **不在表内的活动事件名**：`agentStore` 会把 SSE `error` 派生成一条 `warning` 活动行
> （`store/agentStore.ts:272`），`abort()` 也会写 `cancelled` 活动行。`warning` 不是线上 SSE 事件名，
> 本映射**没有**登记它；调用方如果拿活动事件名直接映射，会得到 `unknown`。接时间线时应先把活动事件
> 归一成上述事件名，或显式把 `warning` 视为 `degraded`。

## 5. 调用方式

```ts
import { toRunState } from '../lib/runState';

const mapped = toRunState('agent-run', run.status);
// mapped = { state: 'degraded', source: 'agent-run', raw: 'ABANDONED' }
```

| 场景 | 返回 |
| --- | --- |
| 已登记取值 | `{ state: 'failed', source: 'agent-run', raw: 'FAILED' }` |
| 未登记取值 | `{ state: 'unknown', source: 'agent-run', raw: '...' }` |

`source` 是封闭联合（`agent-run` / `resource` / `sse`），传错属于调用方 bug，会大声失败；
**未知取值不会抛异常**，只会落到 unknown。`raw` 对非字符串输入按 `String()` 归一
（`null` / `undefined` 分别是字面量 `'null'` / `'undefined'`）。

## 6. 代码与表的对照方式

- **资源态**：`runState.ts` 的 `RESOURCE_STATES` 用 `Record<ResourceState<unknown>['status'], RunState>`；
  `resource.ts` 新增状态时 tsc 会因缺 key 直接失败，强制同步映射。
- **后端运行态**：`AGENT_RUN_STATES` 的 key 是 `AgentRunStatus | 'ABANDONED'`；前端类型新增取值会因
  `Record` 缺 key 失败。后端跨语言新增状态没有编译期护栏，必须同批改映射、单测与本表。
- **SSE**：`SSE_EVENT_STATES` 的 key 是本文件的 `SseEventName` 联合；`EVENT_NAMES` 目前未导出，
  新增事件必须手工同步三处（`api/agent.ts`、本映射、本表）。
- **单测**：`runState.test.ts` 逐项断言三个来源的每个取值 + unknown 兜底：

  ```bash
  cd travel-agent-web
  npx vitest run src/lib/runState.test.ts
  ```

- 门禁：`cd travel-agent-web && npm run lint:ci && npm run typecheck && npm test && npm run build`。

## 7. 现状边界

`components/RunStatus` 仍持有自己的七态 `RunState` 与 `META`（label / tone 与本表一致，但没有 `hint`）。
本文件先作为映射层落地；把组件、`Workbench`、`AgentRunsDrawer`、结果卡与时间线统一到本映射，
属于后续渲染层改动，本表不重复代码。