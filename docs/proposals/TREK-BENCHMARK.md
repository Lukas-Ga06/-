# 对标 TREK 的改进提案

> 对象仓库：`liketrek/TREK`（AGPLv3，NestJS 11 + React 19 + SQLite 自托管旅行规划 PWA，v5.1.0）
> 本文仓库：`travel-agent`（Spring Boot 3 + React 18，AI 个人助理）
> 定位：**不是功能对标清单，而是因果链分析** —— 找出本仓最底层的一个结构性缺口，以及由它派生的三层依赖。
> 所有代码断言均标注 `文件:行号`，可在仓库中直接核对；未核实的外部事实标注「仅 README 宣称」。

---

## 0. 结论先行

本仓的 Agent 能力**不是"缺功能"，而是"没有循环"**。

`PlannerAgent.java:144-246` 的工具调用序列是 **Java 语句硬编码的一次性流水线**；模型唯一能自主发起的工具调用必须 `startsWith("mcp_")`（`IntentAgent.java:65-71`），**8 个内置工具模型一个都调不到**。`AgentPlan.java:20-34` 的数据结构里**没有"工具调用节点"**——计划是"确定好的条目变更列表"，不是"待执行的动作序列"。

这决定了改进的优先级：**先修循环，再谈生态**。而循环一旦开启，会立刻暴露两个必须同步解决的前置条件：

```
地基缺口：没有 Agent 工具调用循环
    │
    ├─① 过程不可见 ── payload 已到前端却被压成一行字符串
    │                  → 循环一开，用户无法信任一个 30 步的黑箱
    │
    ├─② 数据不可信 ── 4 城 57 条 POI + haversine 假路线
    │                  → 循环再强也只是在假数据上打转
    │
    └─③ 变更不可逆 ── 状态机装饰化 + 撤销只覆盖行程条目
                       → 循环产生的变更无法观测、无法安全回退
```

TREK 的护城河是**产品完成度与生态**（88M 地点索引、410 工具、28 语言），本仓的护城河是**Agent 编排深度**（HITL 三级风险、Plan Diff、指纹校验、操作历史）。因此正确的做法不是横向补齐 TREK 的功能广度，而是**把 TREK 的纵深嫁接到本仓的 Agent 主线上**。

---

## 1. 现状事实（三份只读核查的合并结论）

### 1.1 本仓「真正有的」——不应在提案中重复承诺

| 能力 | 证据 | 备注 |
|---|---|---|
| 结构化规划的**四道校验**全部在位 | `StructuredPlanner.java:407-420`（`match()` 白名单，四个 `convert*` 方法在 `160-224` 调用）、`:139-143`（天数边界）、`:454-462`（`validTime` 校验 `HH:mm` 且 start<end）、`:43` 声明 `MAX_ITEMS=60` / `145-148` 使用 | 模型编造实体被拒，坐标/价格强制取自目录 |
| 一次带反馈重试 + 回落规则 | `StructuredPlanner.java:80-100`、`:290-292` | warning 塞回 prompt 重试一轮 |
| plan **指纹校验**真实生效 | `AgentEngine.java:537-550`，来源 `ItineraryService.java:193-208` | 指纹串为 `b={budget}\|s={startDate}\|e={endDate}\|{id}:{day}:{title}:{start}:{end}:{cost}:{type}`（条目按 id 升序 nullsLast），sha256 后取**前 16 字节的 hex（32 字符）**，确认时重算比对 |
| 只读强制在**注册期**成立 | `ToolRegistry.java:33-37` | `!readOnly()` 直接 `return false` |
| 天气真实接入 + 失败降级 | `WeatherService.java:52-63`（Open-Meteo）、`:151-166`（模拟兜底） | `WEATHER_REAL=true` 时真实 |
| LLM 配额落库且多实例共享 | `LlmQuotaService.java:31-50`、`LlmQuotaRepository:21-23` | SQL 原子自增（`ON DUPLICATE KEY UPDATE`）；**但判额与自增非同一原子操作**，高并发下可小幅超额。拒绝的请求不占额度 |
| 路书 PDF 中文探测链 | `TripGuidebookService.java:216-246` | 找不到抛业务异常，不出乱码 |
| 语音输入是**真的** | `VoiceButton/index.tsx:12-33` | Web Speech API，非模拟；不支持的浏览器整颗按钮隐藏（L15-17），属能力探测式降级 |
| 高德行程地图真实实现 | `MapProvider.ts` + `AMapProvider.ts` + `amapLoader.ts` | 有 3D/2D 切换、路线巡航与加载失败提示 |
| MCP 客户端可用且真被用上 | `McpToolBridge.java:45-65`；`AmapDataService.java:170-183` | `mcp_*_` 前缀反射调用高德 MCP |

> **README 基本诚实**：30 条宣称中 ✅20 / ⚠️5 / 🎭2 / 💀0，且主动自曝了 `CONFIRM/COMMIT/REPLAN` 未接线与 Embedding 非语义。这是很罕见的工程透明度，本提案不会把它当靶子。

### 1.2 本仓「看起来有、实际没有」——本次重点

| # | 宣称 | 真相 | 证据 |
|---|---|---|---|
| 1 | "AI 规划"（选工具/看结果再决策） | **硬编码流水线**，模型不能调用内置工具 | `PlannerAgent.java:144-246`；`IntentAgent.java:65-71` |
| 2 | 10 态 Agent 状态机 | **骨架真实推进**（7 态在 `AgentEngine` L72/115/126/167/184/189/196 被 setState），但**确认/提交/重规划三态未接线**，且 state 不进 SSE | `AgentState.java:11-13`（`CONFIRM`/`COMMIT`/`REPLAN` 全仓 0 处引用）；`AgentContext.java:49-58` |
| 3 | HITL 三级风险 | 前端**只认 HIGH**，MEDIUM/LOW 无差异视觉 | `PlanDiff/index.tsx:34`；`global.css:1689-1690`（图标配色同样两条：`:1704-1705`） |
| 4 | 操作历史可撤销 | 仅行程条目；Trip 本体/支出/备忘/日程**裸写无历史** | `ItineraryController.java:34` vs `TripController.java:67-89` |
| 5 | 工具调用过程可见 | payload 已到 `onEvent` 回调边界，但**未越过消费边界**——被 `STAGE_TEXT` 压成一行字符串丢弃 | `api/agent.ts:65-81`（L79 传完整对象）；`agentStore.ts:62-74,121-127`；`ChatPanel/index.tsx:68-77` |
| 6 | 语义 RAG | 默认是**字符 bigram 哈希**，且失败后**进程内永久锁定** | `LocalHashEmbeddingService.java:12`；`OpenAiCompatibleEmbeddingService.java:82-84` |
| 7 | 多渠道同一套机制 | Telegram/微信/Webhook **都不支持确认与撤销** | 三渠道均只回"请去网页端确认" |
| 8 | MCP 工具生态 | **只有 client**，全仓无入站 server 端 | `mcp/` 6 文件全为客户端 |
| 9 | 实时 POI / 价格 / 路线 | 4 城 57 条 POI；`12 + 2.4*km` 硬编码估价 | `travel-catalog.json`(15KB)；`RouteService.java:20-26` |

> 注意分类差异：第 1、4、7 条不是"没实现"，而是**实现了一条与宣称不同的路径**。这类"架构性空转"比单纯的未实现更难发现，也是按"宣称→实现"二元判定会漏掉的。

### 1.3 TREK 的真实机制（源码证实，非 README 转述）

| 主题 | 关键做法 | 移植到 Java/Spring 的代价 |
|---|---|---|
| AI 写操作 | **复用 MCP 工具面**；`readOnlyHint` 决定"立刻执行 vs 变卡片"；~60 条**带理由**的 denylist；`UNDO_WINDOW_MS=15min`；`MAX_STEPS=30` | 中偏高：`ToolCards` 鸭子类型接口在 Java 里需拆成接口 + 责任链 |
| MCP server | 自研装饰器框架；scope 分 17 组 36 常量；**编译期 `AssertExact` + 启动期 `validateAccess` 双重锁死** | 中等：Spring 注解 + `BeanFactory` 扫描可映射，Java 无 `AssertExact` 需启动期兜底 |
| 撤销 | **不是事务回滚**，是调用对称删除工具；`unchangedSinceApplied()` 撤销前重读比对稳定字段，若用户此后动过关联数据则**拒绝撤销** | 低：一个可测试的纯函数 |
| 离线 | 幂等键 + `X-Base-Updated-At` CAS + 临时负 id 重映射；**服务端无关** | 低到中：Spring 只需幂等去重表 + 乐观锁拦截器 |
| 实时协作 | **不是 OT/CRDT**，纯 room broadcast + 客户端 refetch，正确性交给 CAS | 低 |
| 质量门 | **棘轮**：baseline JSON 只能 shrink，15+ 项指标只准变好 | 低到中：需要一次基线测量 |
| 插件 | 一进程一插件 + env 白名单 + **host 侧读 `/proc/<pid>/VmRSS` 硬杀**（不信自报） | 高：JVM 无等价物，需独立容器 + egress 代理 |

### 1.4 TREK 自己没做好的（避免盲目模仿）

1. **WS 无合并语义**：纯广播 + refetch，两人同改靠 409 手选，不是 Google Docs 式并发。
2. **撤销有条件**：动过关联数据就撤不回来，且仅 15 分钟内；README 措辞听起来像保证。
3. **`live re-register` 实为"全断重连"**：`invalidateMcpSessions()` 无条件杀所有会话，翻开关会踢掉全部在线 MCP 客户端。
4. **三处人工清单都靠 boot 期测试守着，且都漂移过**（addon gate 曾漏 airtrail/collections；plugin hook 三份副本曾不一致；assistant denylist）。
5. **`mutationQueue.ts` 1000 行**，幂等/CAS/temp-id/锁/退避/兼容全挤在一个文件里。
6. **`packing.listBags` 挂在 `db:write:*` 上**是为向后兼容牺牲权限准确性的有意越权。

> **反直觉结论**：TREK 最值得学的**不是**"手写清单 + 测试兜底"这个模式（它出现了三次且都有漂移史），而是它更硬的成果——**编译期锁死 + 启动期语义校验**。

---

## 2. 改进路线（四层，按月分批）

工作量口径：1 名有经验的全职工程师（后端为主，前端配合）。

### L1 · 地基：让 Agent 真的有循环（M1-M2，约 6-8 周）

这是**唯一的主线立项**，其余三层都是它的前置条件或配套。

#### L1-1 ReAct 式工具调用循环

| 项 | 内容 |
|---|---|
| **目标** | 模型能自主选择内置工具、观察结果、再决策；一次请求内可多步 |
| **现状差距** | `PlannerAgent.java:144-246` 硬编码；`IntentAgent.java:65-71` 仅放行 `mcp_*`；`AgentPlan.java:20-34` 无工具节点 |
| **技术方案** | ① `AgentPlan` 增加 `List<PlanStep>`（tool / args / observation），与现有 `List<PlanOperation>` **并存**；② 新增 `ToolLoopExecutor`，以 `MAX_STEPS`（建议 12）驱动"选工具→调用→观察→再选"；③ 放宽 `IntentAgent` 的工具白名单为**注册表全量**，靠 `readOnly()` 保证安全（写操作仍然只能表达为 `PlanOperation`，**不触碰 `ToolRegistry` 的硬闸门**）；④ 现有规则流水线**完整保留**为降级路径（`llm == null` 或循环超限时回落） |
| **硬约束（不可违反）** | `ToolRegistry.java:33-37` 对 `!readOnly()` 直接拒绝——**不要试图给 Agent 加写工具**，写仍走 `AgentPlan` |
| **验收标准** | ① 单请求内至少 2 次**依赖式**调用（第二次的参数来自第一次结果）；② 8 个内置工具均可被模型选中（日志可见 `tool_call` 事件名）；③ 未配置 LLM 时行为与改造前**逐字等价**（用现有测试快照对比）；④ `MAX_STEPS` 超限时产出明确失败语义而非异常 |
| **风险** | 模型乱调工具导致成本上升 → 需同步上线 L1-2 的可观测性；工具结果过大撑爆上下文 → 需 `MAX_TOOL_OUTPUT_CHARS` 截断 |

#### L1-2 状态机接线

| 项 | 内容 |
|---|---|
| **目标** | `CONFIRM`/`COMMIT` 接入状态机并进入 SSE，使进度可观测、断点可续 |
| **现状差距** | 状态机**骨架是真实工作的**——7 个状态（含初始 `RECEIVE`）在 `AgentEngine` L72/115/126/167/184/189/196 被 `setState` 真实推进。缺的是**末段三态**：`CONFIRM`/`COMMIT`/`REPLAN` 全仓 0 处引用（`AgentState.java:11-13`），状态机在 PRESENT 处断头；且 `AgentContext.java:49-58` 的 `emit()` 只推 event+data，`state` 字段（`AgentContext.java:35`）从不随事件下发 |
| **技术方案** | ① `AgentEngine.confirm()` 内 setState(CONFIRM/COMMIT)；② `AgentContext.emit()` 增加 `state` 字段；③ 新增 `state` SSE 事件；④ `REPLAN` 要么接入 `REPLAN` 语义（见 L4-1），要么**从枚举中删除**——不允许继续留死值 |
| **验收标准** | ① 一次完整请求的 SSE 流中，state 迁移序列可被前端记录并断言；② 前端 `agentStore` 有对应字段；③ 全部 10 个枚举值均有真实引用（可用一条测试守护，现有 7 态不得回归） |

#### L1-3 撤销全覆盖 + 真栈

| 项 | 内容 |
|---|---|
| **目标** | 撤销从"只保护行程条目"扩展到全部可变更实体；支持多步与 redo |
| **现状差距** | `ItineraryController.java:34` 有 `withHistory`；`TripController.java:67-89`、`ExpenseController.java:40-67`、`MemoController.java:40-57`、`ScheduleController.java:47-68` **全部裸写无历史**；undo 只能退一步、无 redo、`replaceAll` 全表重建致 ID 全变（`ItineraryService.java:136-145`） |
| **技术方案** | ① 把 `withHistory` 从行程条目提升为**可复用切面**，覆盖 Trip 本体（预算/日期/状态）、支出、备忘、日程；② 引入 **TREK 式前置校验** `unchangedSinceApplied()`——撤销前重读并比对关联数据快照，若用户此后动过（如已挂账单/预订）则**拒绝并说明原因**，而非静默连带删除；③ 历史记录增加 `UNDONE → REDONE` 状态迁移，支持 redo 与历史列表 API |
| **验收标准** | ① 改预算 → 可撤销 → 预算恢复原值；② 新建行程并记账后撤销 → 明确拒绝并给出原因（**不允许**连带删账单）；③ 撤销历史列表可查询，至少 10 步；④ 现有 `AGENT_COMMIT`/`MANUAL_EDIT`/`TRIP_CREATE` 三种语义不回归 |

---

### L2 · 可观测与安全闸门（M2，约 2-3 周）

**这一层成本最低、收益最直接，建议与 L1 并行启动**——否则 L1 一上线就是黑箱。

#### L2-1 工具调用卡片化（纯前端改动）

| 项 | 内容 |
|---|---|
| **目标** | `tool_call`/`tool_result` 可展开、可累积、不互相覆盖 |
| **现状差距** | `api/agent.ts:65-81` 把每个事件的**完整 data 对象**交给 `onEvent`（L79，另有 POST 版 L176 同构），但 `agentStore.ts:121-127` 是唯一消费点，只调用 `STAGE_TEXT` 取回一个字符串，其余字段**就地丢弃**。注意 `STAGE_TEXT` 只有 **8 个键**（缺 `focus`/`complete`/`error`，它们走别处）。因此 payload 完整到达了**传输边界，但没有越过消费边界**——数据只在前端存活于函数实参的瞬间，从未被持久化 |
| **技术方案** | ① **第一步（前置）**：冻结 `tool_call`/`tool_result` 的 payload schema——当前 `data: any`、字段名无任何 TS 约束，不冻结则卡片建立在猜测的字段名上；② `agentStore` 增加 `toolSteps: ToolStep[]` 累积数组（替代"只有单个 `stage`"的现状），在执行点 `agentStore.ts:121-127` 分流；③ 新增 `ToolStepCard` 组件：工具名 / 参数（可折叠）/ 结果摘要 / 耗时 / 状态；④ 保留 `stage` 作为末行摘要兼容现状；⑤ 记得同步 `reset`/`newConversation`/`complete` 三处清理逻辑与类型定义、初值 |
| **验收标准** | ① 一次 3 次工具调用的对话，界面上可见 3 张卡片而非 1 行文字；② 卡片可展开看到参数 JSON；③ 卡片在 `complete` 后仍保留可回溯；④ `ToolStep` 类型为字面量约束而非 `any` |
| **工作量修正** | 仍为**纯前端 1 周**（后端零改动），但性质是"新建一条采集链路"，**不是"接一下已有的数据"** |
| **为什么是 P0** | L1-1 开启循环后，一次请求可能有 12 步。没有卡片，用户面对的是纯黑箱 |

#### L2-2 风险分级真实化

| 项 | 内容 |
|---|---|
| **目标** | README §4.2 承诺的 LOW/MEDIUM/HIGH 三级在界面上**真实可分** |
| **现状差距** | `PlanDiff/index.tsx:34` 只判 `riskLevel === 'HIGH'`；`index.tsx:17` 让 MODIFY 与 ADD 共用绿色；`global.css:1689-1690` 只有 remove/add 两色（图标配色 `:1704-1705` 同） |
| **待裁决项** | 后端 `SafetyInterceptor.evaluatePlanChange()`（`SafetyInterceptor.java:31-39`）确实产出三级（超预算→HIGH / 普通修改→MEDIUM / 只读→LOW），因此这是**前端渲染缺失**，非后端问题 |
| **技术方案** | ① 三级各自配色与图标；② MODIFY 与 ADD 视觉分离（目前同为 `add` 类）；③ `types/agent.ts:37` 的 `riskLevel?: string` 收窄为字面量联合类型 |
| **验收标准** | ① 三种 riskLevel 截图各不同；② 一份 MEDIUM 方案不会渲染成与 HIGH 相同；③ TypeScript 层面拒绝未知 riskLevel |

---

### L3 · 可信数据（M3-M4，约 6-8 周）

#### L3-1 真实 POI / 路由 / 价格

| 项 | 内容 |
|---|---|
| **目标** | 摆脱"4 城 57 条 + haversine 估算" |
| **现状差距** | `travel-catalog.json`(15KB)；`RouteService.java:20-26` 纯 haversine + `12 + 2.4*km`；酒店/餐厅无真实价格源 |
| **技术方案** | ① **优先复用已在途的高德接入**（`AmapDataService` 已存在，见 §4 待办）；② 目录数据源抽象为 `PlaceProvider` 接口，静态 JSON 与高德实现并存，按城市覆盖度路由；③ 路由抽象为 `RouteProvider`（haversine 保留为离线降级）；④ 价格**必须标注来源与采集时间**，无来源时显示"估算"而非伪装成真实价格 |
| **验收标准** | ① 目录覆盖城市数 ≥ 20；② 高德可用时路线耗时来自真实引擎（与 haversine 结果有统计差异）；③ 每个价格字段带 `source` 与 `fetchedAt`；④ 外部服务不可用时**明确降级提示**，不静默返回假数据 |
| **风险** | 高德/OSM 配额与合规 → 需缓存层与限流；境外城市覆盖 → 逐步扩而非一次到位 |

#### L3-2 RAG 降级不可见问题

| 项 | 内容 |
|---|---|
| **目标** | 真实 embedding 失败时**可见**，而非进程内永久静默锁定 |
| **现状差距** | `OpenAiCompatibleEmbeddingService.java:82-84` 失败即 `degraded = true` 直到重启；`InMemoryVectorStore.java:53-67` 无相似度阈值，cosine 为 0 也返回 |
| **技术方案** | ① 降级状态暴露到 `GET /api/system/status` 与启动日志；② 增加相似度阈值过滤；③ 允许后台重试恢复，而非只能重启 |
| **验收标准** | ① 模拟 embedding 故障后，`/api/system/status` 可见降级标记；② 阈值以下结果不返回；③ 故障恢复后无需重启即恢复真实检索 |

---

### L4 · 生态纵深（M5+，视 L1 成效决定是否启动）

| 立项 | 目标 | 现状 | 关键取舍 |
|---|---|---|---|
| **L4-1 入站 MCP server 端** | 让外部 AI 能 list/call 本仓的行程工具 | 全仓只有 client（`mcp/` 6 文件全为客户端） | **建议先读 L1 成效**：循环没打通时，导出工具面意义有限 |
| **L4-2 多渠道确认/撤销闭环** | Telegram 内可确认与撤销 | 三渠道均只回"请去网页端确认" | 成本低、收益明确，可与 L2 并行 |
| **L4-3 差异化 AI 能力** | 跨行程问答（"我今年旅行花了多少"） | `AgentEngine.java:257-263` 强制要求单一行程；统计能力存在于 `ExpenseService.java:92-146` 但对 Agent 不可达 | **建议在 L1 之后立刻做**：这是"个人助理"叙事最好的落点，且后端能力已就绪，只差意图与工具 |

---

## 3. 与 TREK 的差异化定位（不要做的事）

| TREK 有 | 本仓是否应该做 | 理由 |
|---|---|---|
| 离线写队列三件套 | **暂不做**，但记入路线 | 服务端无关、概念可整体照搬，但前端需从零建 IndexedDB 层。当前零 `navigator.onLine`/IndexedDB，投资回报在循环打通前偏低 |
| 实时协作（WebSocket） | **暂不做** | TREK 自己也只做到"广播 + refetch"，无合并语义。本仓是单人助理定位，优先级低于 Agent 质量 |
| 28 语言 i18n | **暂不做** | 中文硬编码遍布 40 个 tsx，属全量返工；且产品定位是中文用户 |
| 插件体系 | **不做** | JVM 无 `fork` + `--permission` 等价物，需独立容器 + egress 代理，成本远超收益 |
| 88M 地点索引 | **不做**，改为接入 | 自建索引不现实，接高德/OSM 即可 |
| TREK Studio（相册书） | **不做** | 与"个人助理"主线正交 |
| 棘轮质量门 | **建议做** | 成本低、长期收益高，且与 L3 数据真实性配套（防止回退到假数据） |

---

## 4. 前置待办（动手前必须解决）

1. **厘清在途改动归属**：`git status` 显示 3 个未提交文件（`AmapPoi.java`、`AmapRoute.java`、`AmapDataService.java`）与未跟踪目录 `web-course/`。L3-1 要动高德接入，**不能建立在未定归属的半成品上**——先确认这些改动是保留、完成还是丢弃。
2. **`web-course/` 的去向**：未跟踪目录，需确认是否入库。
3. **`McpProperties.java:42` 的 `readOnly = true` 默认放行**：虽然 `ToolRegistry` 闸门实际拦截有效，但拦截只发生在启动日志里，用户侧静默。建议改为启动期显式校验并 warn。
4. **`GetItineraryTool.java:36-40` / `CalculateBudgetTool.java:37-41` 无 tripId 归属校验**：仅靠审计日志，建议补权限校验。
5. **`/ui-demo` 页面**（`pages/UIDemo/index.tsx:19` 假数据）挂在受保护路由上，建议下线。

---

## 5. 排期总表

| 批次 | 立项 | 工作量 | 依赖 | 关键风险 |
|---|---|---|---|---|
| M1 | L1-2 状态机接线 | 1 周 | 无 | 低 |
| M1 | L2-1 工具卡片（纯前端） | 1 周 | 无 | 低 |
| M1 | L2-2 风险分级 | 3 天 | 无 | 低 |
| M1-M2 | **L1-1 ReAct 工具循环** | 4-6 周 | 前置待办 §4 | 模型成本、上下文膨胀 |
| M2 | L1-3 撤销全覆盖 | 3-4 周 | 状态机接线 | `replaceAll` 全表重建的 ID 变更 |
| M3 | L3-2 RAG 降级可见 | 1 周 | 无 | 低 |
| M3-M4 | L3-1 真实数据源 | 6-8 周 | 前置待办 §4.1 | 配额/合规/境外覆盖 |
| M4 | L4-2 多渠道闭环 | 2 周 | L1-3 | 低 |
| M4 | L4-3 跨行程问答 | 2-3 周 | L1-1 | 低（后端能力已就绪） |
| 视成效 | L4-1 入站 MCP server | 4-6 周 | L1-1 成效评估 | 生态定位需想清 |
| 长期 | 棘轮质量门 | 2 周建基线 + 持续 | 无 | 低 |

**建议起步顺序**：L1-2 + L2-1 + L2-2（合计约 2 周，都是小改动且立刻可见价值）→ 借此建立信心与可观测性 → 再启动 L1-1 这个真正的硬骨头。

---

## 6. 验收方式

本提案的每一项都由以下方式验证，不接受"自报完成"：

1. **L1-1**：对比改造前后同一提示词的 SSE 事件序列，证明工具调用由模型发起且可多步；未配 LLM 时用现有测试快照证明逐字等价。
2. **L1-2**：SSE 流中 state 迁移可被录制并断言；一条测试守护"枚举无零引用值"。
3. **L1-3**：三类撤销场景的集成测试（预算/支出/日程 + 拒绝撤销场景）。
4. **L2-1**：截图对比 + 组件测试证明 3 次调用产生 3 张卡片。
5. **L2-2**：三种 riskLevel 的渲染快照各不相同。
6. **L3-1**：价格字段的 `source`/`fetchedAt` 存在性测试；路线结果与 haversine 的统计差异测试。
7. **L3-2**：故障注入测试证明降级可见且可恢复。

---

## 附录 A：证据来源

| 来源 | 性质 | 覆盖 |
|---|---|---|
| 后端事实核查 | 只读代码审查，45 文件 / 31 命令 | `travel-agent-server/src/main/java/com/travel/agent/` |
| 前端事实核查 | 只读代码审查，29 文件 / 9 命令 | `travel-agent-web/src` + 构建配置 |
| 宣称对账 | 文档 ↔ 代码逐条比对 | README 30 条宣称 |
| TREK 拆解 | 24 个 URL 抓取，21 个拿到源码原文 | 7 个主题 + 缺陷清单 |

## 附录 B：未核实项（诚实标注）

- TREK 的 PKCE 强制、410/30/4 工具数、381 篇帮助中心、Sonar 质量门阈值、28 语言翻译质量 —— **仅 README 宣称**，未在源码验证。
- 本仓 `PlanDiff` 的 `riskLevel` 实际取值集合已由后端证据确认（三级均会产出），前端缺失属实现遗漏。
