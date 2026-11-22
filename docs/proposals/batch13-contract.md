# 第十三批共享契约（Batch 13 shared contract）

> 状态：已落地（历史） | 日期：2026-11-18 | 说明：契约内容已进入实现，保留原文作追溯；当前事实见 [docs/guide](../guide/README.md)，落地记录见 [CHANGELOG 第十三批](../CHANGELOG.md)。

本批只做一件事的两半：**让 FR-06 的审核结论真正改变结果**，并把已有的
`GET /api/trips/{id}/route-legs` 接到时间线 UI。FR-01（人数槽位/最少追问）不在本批范围——
它要加字段与 Flyway 迁移，单独一批。

## 0. 范围与边界

- 后端：`ReflectAgent` 新增「转场可行性（距离/时长）」与「雨天户外」硬约束；
  新增确定性修复器；`AgentEngine` 在 HIGH 未通过时进入 `AgentState.REPLAN`（首次真正使用该枚举）
  做**有限次**（≤2）重规划，重排后再审；仍未通过则在确认门上要求**显式接受风险**。
- 前端：时间线插入「转场」行（距离/步行/驾车/打车估算 + 来源标记）；地图按路线段绘制
  （`estimated` 虚线、`amap` 实线）；确认弹窗展示审核结论，未解决 HIGH 问题时必须勾选确认才能提交。
- 不改：`PlanOperation`/commit/undo 语义、`PendingPlanStore.claim/release` 与 TTL、
  `planRevision` 校验、预算三口径（计划/预算/实际）、`route-legs` 现有响应形状。
- 不新增数据库表/列，因此**没有 Flyway 迁移**。

## 1. 后端 → 前端：`AgentResult` 新增 `review`

`AgentResult` 只是「新增字段」，旧前端不读也不会坏；字段为空表示旧后端/无需审核。

```jsonc
{
  "planId": "...",
  "status": "NEED_CONFIRMATION",
  "review": {
    "passed": false,          // 最终是否仍有 HIGH 级问题
    "blocked": true,          // true = 存在未解决 HIGH 问题，确认必须显式接受风险
    "attempts": 2,            // 已执行的重规划次数（0 表示一次也没重排）
    "issues": [               // 最终一轮审核的问题清单（passed=true 时也可能有 MEDIUM）
      { "type": "TRANSFER_TIME", "severity": "HIGH", "message": "Day 1「外滩」→「迪士尼」转场需 65 分钟，但只留了 10 分钟" }
    ],
    "repairs": [              // 自动修复动作（人话，直接可展示）
      "Day 1 把「外滩」之后的安排整体后移 60 分钟",
      "Day 2 将雨天户外景点「野生动物园」换成室内景点「上海博物馆」"
    ]
  },
  "confirmation": {
    "planId": "...",
    "summary": "...",
    "riskLevel": "HIGH",                    // blocked 时恒为 HIGH
    "contentHash": "...",
    "requiresAcknowledgement": true         // = review.blocked；前端据此要求勾选
  }
}
```

### issue.type 取值

| type | 含义 | 严重级 |
|---|---|---|
| `BUDGET_OVERRUN` | 计划合计超预算（既有） | HIGH |
| `TRANSFER_TIME` | 相邻安排之间转场时间不够 | HIGH |
| `WEATHER_OUTDOOR` | 雨天（降雨概率 ≥60% 或天况含「雨」）仍排户外景点 | HIGH |
| `TIME_CONFLICT` | 时间重叠（既有软提醒） | MEDIUM |

> 天气来自 `WeatherService`：`travel.weather.real=false`（默认）时是**确定性模拟数据**，
> issue 文案会带「（模拟天气）」字样——这是 ADR-006「估算必须自报家门」的延续。
> `TRANSFER_TIME` 的时长来自 `RouteService`（高德真实路径或 haversine 估算，`source` 同理）。

### 重规划事件（SSE）

新增事件 `replan`，`data = { "attempt": 1, "issues": ["..."] }`，每次进入 REPLAN 发一次。
前端只需：① 单行 stage 文案；② 过程时间线（AgentActivityPanel）一行。

## 2. 前端 → 后端：确认请求新增 `acknowledgeIssues`

`POST /api/agent/confirm`：

```jsonc
{
  "planId": "...",
  "contentHash": "...",          // 既有，原样回传
  "acknowledgeIssues": true      // 新增，可选；blocked 方案必须为 true
}
```

- `review.blocked=true` 且未携带 `acknowledgeIssues=true` → 后端拒绝（业务失败，`status=FAILED`），
  提示「存在未解决的高风险问题，请调整需求或明确接受风险后再确认」；此时方案**不被消费**
  （`release` 回 PENDING，可改携带 ack 重试）。
- `acknowledgeIssues` 缺省/false 时行为与现在完全一致（旧前端零改动）。
- `GET /api/agent/plans/{planId}` 取回的 `preview` 同样带 `review` 与
  `confirmation.requiresAcknowledgement`，刷新页面后仍能正确要求勾选。

## 3. 路线段（FR-07）：沿用既有端点，不加字段

`GET /api/trips/{id}/route-legs` 已返回：

```jsonc
[{ "dayIndex": 1, "sequence": 1, "fromItemId": 3, "toItemId": 4,
   "fromTitle": "...", "toTitle": "...",
   "fromLatitude": 31.23, "fromLongitude": 121.47, "toLatitude": 31.24, "toLongitude": 121.48,
   "distanceKm": 4.2, "walkMinutes": 56, "driveMinutes": 12, "taxiCost": 22,
   "source": "amap" | "estimated" }]
```

前端要求：

- `Timeline` 在 `fromItemId` 与 `toItemId` 对应的两条安排之间插入转场行：
  `🚕 4.2 km · 驾车 12 分钟 / 步行 56 分钟 · 约 ¥22`；
  `source=estimated` 时显示「估算」徽标，`amap` 时显示「实测」徽标（不得把估算显示成实时）。
- 地图：按路线段把当天折线拆成逐段绘制；`estimated` 用虚线（dashArray），`amap` 用实线；
  同一条 day 的分段颜色与现有一致（`DAY_COLORS`）。无 legs 数据时保持现有「点位连线」行为。
- legs 拉取失败/为空不得阻塞时间线与地图渲染（静默降级）。

## 4. 验证口径

- 后端：`mvn clean test` 全绿；新增用例覆盖
  「转场不可能 → HIGH」「雨天户外 → HIGH」「重规划后通过（attempts≥1, repairs 非空）」
  「修不好 → blocked=true」「未 ack 确认被拒 / ack 后成功」。
- 前端：`npm run typecheck && npm test` 全绿；新增 Timeline 转场行、地图分段线、PlanDiff 勾选门槛用例。
- 真实 HTTP 冒烟（默认 profile）：「预算 1 元」规划 → `review.blocked=true`，
  不带 ack 确认 → `FAILED`；带 ack → `COMPLETED`。