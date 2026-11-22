# 第十四批共享契约（Batch 14 shared contract）：FR-01 需求槽位与最少追问

> 状态：已落地（历史） | 日期：2026-11-18 | 说明：契约内容已进入实现，保留原文作追溯；当前事实见 [docs/guide](../guide/README.md)，落地记录见 [CHANGELOG 第十四批](../CHANGELOG.md)。

本批只做一件事：**把「一句话输入」的缺信息体验补上——人数槽位 + 最少追问**，
并把同行人数贯通到行程、预算与路书。承接 [v2.2-iteration.md](../guide/v2.2-iteration.md) §7。

## 0. 范围与边界

- 新增 `RequirementSlots`（destination / days / partySize / budget / startDate）：
  - 规则解析（`MessageParser`）与 LLM JSON（`IntentAgent` prompt 的 `slots` 字段）**同时产出**；
  - 跨轮累积的依据是会话历史里「上一轮助手是需求追问」（`intent=PLAN_TRIP_NEED_INFO`）的标记，
    只读该片段内用户自己说过的话；用户改聊别的即自然失效（ADR-010）。
- `AgentEngine.planAndPresent` 前置检查：缺**必填槽位（目的地/天数/人数）**时做**一次合并追问**，
  不启动规划；用户明确拒绝（随便/你决定…）或追问达 2 轮仍未齐 → 按默认值继续，并在回复里**明示兜底项**。
- `TripDraft` / `Trip` 增加 `partySize`（V17 迁移，H2/MySQL 双方言）；确认后落到 `trip.party_size`。
- 预算口径下沉到 `BudgetService`：酒店按双人间计房数（`roomsFor`），餐饮/门票按人头（`perPerson`），
  交通整车不按人数放大；规则规划器与 LLM 结构化规划器共用同一套静态方法。
- 不改：确认门 / `PlanOperation` / commit / undo 语义、`PendingPlanStore` 与 TTL、预算三口径、
  历史「只做指代、不进规划产物」的既有边界（本批只对**同一次规划片段**开一个窄口子，见 ADR-010）。

## 1. 需求槽位与追问（后端）

### 1.1 必填/可选

| 槽位 | 必填 | 缺失时 |
|---|---|---|
| destination 目的地 | 是 | 追问「去哪里？」；拒绝回答时兜底 `上海` 并明示 |
| days 天数 | 是 | 追问「玩几天？」；兜底 3 天并明示 |
| partySize 人数 | 是 | 追问「几个人一起（儿童也算人数）？」；兜底 1 人并明示 |
| budget 预算 | 否 | 直接按 ¥5000 估算，文案明示「预算按 ¥5000」 |
| startDate 出发日期 | 否 | 直接按明天，文案明示「出发日期按 M月d日」 |

### 1.2 追问轮次

- 第 1 轮缺槽：合并追问，`status=COMPLETED`、`missingSlots=[...]`，**不产生 planId / 不写 pending_plan**。
- 追问后用户的短回答（「上海」「三天」「两个人」）按 PLAN_TRIP 续槽；改问别的意图不劫持。
- 三个必填槽位最多分三轮补齐；**某轮没补到新信息、或已连续追问 3 轮**（`MAX_REQUIREMENT_ASKS=3`）
  仍缺 → 走默认值兜底，回复里逐项写明兜底值。
- 极短回答的解析：整句 2-4 个纯汉字且不像天数/人数/预算时才当城市名（「上海」→ 城市；
  「三天」「两个人」「你决定吧」都不是城市）。
- 直接调用 `PlannerAgent`（单测/旧调用方）时自行解析并兜底，行为与升级前一致。

### 1.3 `AgentResult` 新增 `missingSlots`

```jsonc
{
  "intent": "PLAN_TRIP",
  "status": "COMPLETED",
  "message": "开始规划前，还需要确认：\n· 去哪里？\n· 玩几天？\n· 几个人一起（儿童也算人数）？\n预算和出发日期也可以顺手说；不说的话我按 ¥5000、明天出发估算。",
  "missingSlots": ["destination", "days", "partySize"]
}
```

- `missingSlots` 非空 = 本轮**没有规划**（无 `planId` / `confirmation` / `diff`）。
- 缺省/空数组 = 正常规划路径，行为与旧后端一致（旧前端不读该字段也不受影响）。
- 追问轮次的助手消息以 `intent=PLAN_TRIP_NEED_INFO` 落 `conversation_message`（前端展示不受影响）。

## 2. 行程与预算（后端）

```jsonc
// TripRequest（POST/PATCH /api/trips）新增可选字段
{"partySize": 3}   // 1..20，缺省 1；非法值 400

// TripResponse 新增
{"partySize": 3}
```

预算口径（`BudgetService`，两条规划路径共用）：

| 条目 | 口径 |
|---|---|
| 酒店 | `每晚房价 × 房间数 × 晚数`，房间数 = `ceil(人数 / 2)`（双人间，至少 1 间） |
| 餐饮 | `人均 × 人数` |
| 景点门票 | `门票 × 人数` |
| 市内交通/打车 | 整车费用，不按人数放大 |

- 存量行程 `party_size=1`（V17 回填），升级前后单人成本口径完全一致。
- 酒店条目 tags 追加 `N间房`（N>1 时），路书/差旅展示可直接看到房数。

## 3. 验证口径

- 后端：`mvn clean test` 全绿；新增
  「槽位解析（一次说全/经典句式/空请求/表单成人儿童/口语变体/极短回答/合并/兜底/拒绝识别）」10 例、
  「合并追问 / 跨轮补槽 / 一次成槽 / 拒绝后兜底」4 例。
- 迁移：V17 在全新 H2 库与存量库（Flyway 只跑增量）都能通过；`MigrationBaselineTest` 版本面更新。
- 前端：`npm run typecheck && npm test && npm run build` 全绿；行程页/路书展示人数与房数。
- 真实 HTTP 冒烟：「帮我规划行程」→ 追问；「三个人下周六去北京两天」→ 一次成槽并明示默认预算；
  「你决定吧」→ 兜底继续；确认后 `trip.partySize` 与预算房数正确。