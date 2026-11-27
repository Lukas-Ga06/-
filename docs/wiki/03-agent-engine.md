# 03 · Agent 引擎（agent 包）

> 这是全系统的核心。设计主线一句话：**LLM 只产出计划与文案，数据写入永远走「预览 → Diff → 用户确认 → commit」的确定路径**。
> 源码目录：[agent/](../../travel-agent-server/src/main/java/com/travel/agent/agent/)

## 3.1 状态机

[AgentEngine](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentEngine.java) 是唯一的编排入口，被 Web（`AgentController`）与全部外部渠道（Telegram/微信/Webhook）复用：

```
RECEIVE → UNDERSTAND → PLAN → EXECUTE → REFLECT → PRESENT → CONFIRM → COMMIT
                     └──────── REPLAN（审核不通过，≤2 次）────────┘
异常路径：任意阶段 → ERROR → 反馈用户（SSE error 事件 / 同步错误码）
取消路径：阶段边界检查取消标记 → CANCELLED
```

引擎自身只做状态机与编排（取消边界、会话记忆、规划→预览→反思→待确认链路、外部工具调用），具体答复委托给：

| 委托方 | 职责 |
| --- | --- |
| [AssistantIntentHandler](../../travel-agent-server/src/main/java/com/travel/agent/agent/AssistantIntentHandler.java) | 旅行备忘 / 日程提醒类意图 |
| [TravelQueryHandler](../../travel-agent-server/src/main/java/com/travel/agent/agent/TravelQueryHandler.java) | 知识问答 / 预算 / 记账 |
| [PlanConfirmationService](../../travel-agent-server/src/main/java/com/travel/agent/agent/PlanConfirmationService.java) | 确认 / 取消 / 取回 / 撤销 |
| [AgentStageTimer](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentStageTimer.java) | 阶段计时指标 |

## 3.2 四类 Agent

| 类 | 职责 | 关键点 |
| --- | --- | --- |
| [IntentAgent](../../travel-agent-server/src/main/java/com/travel/agent/agent/IntentAgent.java) | 意图识别 | LLM 优先；失败走 [MessageParser](../../travel-agent-server/src/main/java/com/travel/agent/agent/MessageParser.java) 规则解析。产出 [IntentResult](../../travel-agent-server/src/main/java/com/travel/agent/agent/IntentResult.java)（`PLAN_TRIP`/`MODIFY_ITINERARY`/`RECORD_EXPENSE`/`ADD_MEMO`/`ADD_SCHEDULE`/问答等） |
| [PlannerAgent](../../travel-agent-server/src/main/java/com/travel/agent/agent/PlannerAgent.java) | 生成计划 | 经 [StructuredPlanner](../../travel-agent-server/src/main/java/com/travel/agent/agent/StructuredPlanner.java) 结构化规划（LLM），失败回落规则规划器（内置城市目录 + 模板 + 预算测算）；规划期调用只读工具与 RAG；槽位缺失时追问（`PLAN_TRIP_NEED_INFO`，上限 3 轮，[RequirementSlots](../../travel-agent-server/src/main/java/com/travel/agent/agent/RequirementSlots.java)） |
| [ExecutorAgent](../../travel-agent-server/src/main/java/com/travel/agent/agent/ExecutorAgent.java) | 预览与提交 | `preview()`：`AgentPlan` → `PlanDiff`（ADD/REMOVE/MODIFY + 预算前后对比）→ `STATUS_NEED_CONFIRMATION`；`commit()`：区分新建/已有 Trip → 快照 → `ItineraryService.applyOperations()` → 写 TripEvent。**本类不直接操作数据库** |
| [ReflectAgent](../../travel-agent-server/src/main/java/com/travel/agent/agent/ReflectAgent.java) | 审核 | 天气/路线/预算合理性校验；不通过进 `REPLAN`（≤2 次）；真实天气拿不到时如实标注「未做雨天/户外校验」 |

辅助组件：

- [Coordinator](../../travel-agent-server/src/main/java/com/travel/agent/agent/Coordinator.java) / [AgentToolLoop](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentToolLoop.java)：模型驱动的工具调用循环（LLM 决定调哪个只读工具）。
- [PlanReviewLoop](../../travel-agent-server/src/main/java/com/travel/agent/agent/PlanReviewLoop.java) / [PlanRepairService](../../travel-agent-server/src/main/java/com/travel/agent/agent/PlanRepairService.java) / [PlanInspector](../../travel-agent-server/src/main/java/com/travel/agent/agent/PlanInspector.java)：审核循环与方案修复/检查。
- [PendingPlanStore](../../travel-agent-server/src/main/java/com/travel/agent/agent/PendingPlanStore.java)：待确认方案暂存（`pending_plan` 表）。
- [PlannerDraftSupport](../../travel-agent-server/src/main/java/com/travel/agent/agent/PlannerDraftSupport.java) / [TripDraft](../../travel-agent-server/src/main/java/com/travel/agent/agent/TripDraft.java)：行程草稿结构。
- [MessageTemplates](../../travel-agent-server/src/main/java/com/travel/agent/agent/MessageTemplates.java) / [MultiIntentNotices](../../travel-agent-server/src/main/java/com/travel/agent/agent/MultiIntentNotices.java)：模板回复（无 LLM 时的兜底文案）。
- [AgentContext](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentContext.java) / [AgentState](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentState.java) / [AgentEvent](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentEvent.java)：上下文、状态与 SSE 事件模型。
- [AgentBudget](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentBudget.java)：端到端时间预算（默认 30s）。每阶段开始前检查剩余，耗尽即降级并留痕（任务类型 `DEGRADE`、SSE `budget_degraded` 事件、回复文案 ⏳ 前缀）。

## 3.3 核心数据结构

[AgentPlan](../../travel-agent-server/src/main/java/com/travel/agent/agent/AgentPlan.java) 是规划的输出、确认的输入：

```
planId, intent, target, tripId, runId, city,
tripDraft          // 行程草稿（天/条目）
operations[]       // PlanOperation：ADD / REMOVE / MODIFY
reasoning, focus, extras, citations（RAG 引用）
```

[PlanOperation](../../travel-agent-server/src/main/java/com/travel/agent/agent/PlanOperation.java)：最小变更单元，commit 时由 `ItineraryService.applyOperations()` 逐个应用到 `ItineraryItem`，同时记录 before/after 到 `OperationHistory`（撤销的依据）。

## 3.4 工具系统（tool 包）

[AgentTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/AgentTool.java) 接口 → [ToolRegistry](../../travel-agent-server/src/main/java/com/travel/agent/tool/ToolRegistry.java) 注册表 → [ToolInvoker](../../travel-agent-server/src/main/java/com/travel/agent/tool/ToolInvoker.java) 统一调用（含 [SafetyInterceptor](../../travel-agent-server/src/main/java/com/travel/agent/tool/SafetyInterceptor.java) 与 [RiskLevel](../../travel-agent-server/src/main/java/com/travel/agent/tool/RiskLevel.java) 三级风险）。

**铁律：ToolRegistry 只注册只读工具**，写操作工具会被拒绝；写变更必须表达为 `AgentPlan` 操作项经确认落地。

内置 9 个工具（tool/impl）：

| 工具 | 功能 |
| --- | --- |
| [QueryWeatherTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/QueryWeatherTool.java) | 查天气（Open-Meteo 真实数据；拿不到即不可用，不模拟） |
| [SearchAttractionTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/SearchAttractionTool.java) | 景点检索（内置目录 / 高德） |
| [SearchHotelTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/SearchHotelTool.java) | 酒店检索 |
| [SearchRestaurantTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/SearchRestaurantTool.java) | 餐饮检索 |
| [CalculateRouteTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/CalculateRouteTool.java) | 路线计算（高德或本地估算，标注 source） |
| [CalculateBudgetTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/CalculateBudgetTool.java) | 预算测算 |
| [GetItineraryTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/GetItineraryTool.java) | 读取当前行程 |
| [QueryTravelKnowledgeTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/QueryTravelKnowledgeTool.java) | 知识库检索（走 RAG） |
| [OnlineCitySearchTool](../../travel-agent-server/src/main/java/com/travel/agent/tool/impl/OnlineCitySearchTool.java) | 在线城市搜索 |

MCP 扩展：`mcp/McpToolBridge` 把外部 MCP server 工具以 `mcp_<server>_<tool>` 命名注册进同一注册表（默认关闭，`MCP_ENABLED=true` 开启；仓库预置高德 MCP stdio 配置）。

## 3.5 LLM 层（llm 包）

```
LlmClient（接口）
 ├── OpenAiCompatibleLlmClient   真实 HTTP 客户端（默认 DeepSeek）
 ├── RuleBasedLlmClient          规则/模板兜底（无 Key 时）
 └── DelegatingLlmClient         ★ 统一入口：可用性+配额检查 → 委托真实客户端；
                                   失败/未启用/超配额返回 null，调用方走降级路径
```

- [LlmModelProbe](../../travel-agent-server/src/main/java/com/travel/agent/llm/LlmModelProbe.java)：启动后异步 `GET /models` 校验配置模型存在性，结果暴露在 `/api/system/status`（`llmModelAvailable`）。
- 配额：`LlmQuotaService` 按用户每日计数落库（`llm_quota_usage`），超限自动降级、不报错。
- 意图/规划/润色/回顾总结全部计入同一配额。

## 3.6 RAG 层（rag 包）

架构原则：**静态知识走 RAG，实时信息走 Tools**。

- [RagService](../../travel-agent-server/src/main/java/com/travel/agent/rag/RagService.java)：`search()` 向量检索；`searchWithCitations()` 把命中转为 `RagCitation{documentId, chunkId, score, source}`，供 Planner 与问答引用；`ask()` 检索 top3 → 低分过滤（`RAG_MIN_SCORE`，默认 0.05）→ 拼上下文调 LLM；不可用时降级为带来源/版本的知识点列表。
- Embedding：`EmbeddingService` 接口，两实现 —
  - [LocalHashEmbeddingService](../../travel-agent-server/src/main/java/com/travel/agent/rag/LocalHashEmbeddingService.java)：默认基线，字符 bigram 哈希向量（**不是语义检索**，零依赖可跑）；
  - [OpenAiCompatibleEmbeddingService](../../travel-agent-server/src/main/java/com/travel/agent/rag/OpenAiCompatibleEmbeddingService.java)：`EMBEDDING_ENABLED=true` 时启用真实语义向量；失败进程内锁定降级回哈希；**切换模型必须清库重建**（`rag_document`/`rag_chunk`）。
- [InMemoryVectorStore](../../travel-agent-server/src/main/java/com/travel/agent/rag/InMemoryVectorStore.java)（`VectorStore` 接口）：进程内存向量索引，[EmbeddingCodec](../../travel-agent-server/src/main/java/com/travel/agent/rag/EmbeddingCodec.java) 负责序列化。
- 版本化知识库：[RagVersionService](../../travel-agent-server/src/main/java/com/travel/agent/rag/RagVersionService.java)（ACTIVE/INACTIVE 版本）、[RagDocumentUpsertService](../../travel-agent-server/src/main/java/com/travel/agent/rag/RagDocumentUpsertService.java)、[RagChunkRetentionJob](../../travel-agent-server/src/main/java/com/travel/agent/rag/RagChunkRetentionJob.java)（每日清理，除 ACTIVE 外保留最近 N=2 个历史版本块）、[RagBootstrap](../../travel-agent-server/src/main/java/com/travel/agent/rag/RagBootstrap.java)（首启加载 `resources/rag/knowledge.json`）。

## 3.7 关键不变量（改代码前必读）

1. 工具只读；写路径唯一：`ExecutorAgent.commit → ItineraryService.applyOperations`。
2. 行中锁定：重规划只动 `PENDING` 条目；已开始/完成/跳过不被覆盖。
3. 票据只读会话禁止 `RECORD_EXPENSE`/`ADD_MEMO`/`ADD_SCHEDULE` 三类直接写意图（URL 凭证泄漏面大）；规划类可产出 pending_plan，但确认接口 `POST /api/agent/confirm` 不接受票据。
4. 幂等：`AgentCommandIdempotency` 只覆盖记账/备忘/日程三类直接写；规划类天然幂等（pending_plan 可重复改稿）。
5. 预算口径：`planned` 是计划成本、`actual` 是记账合计；预算同步只记快照，绝不自动生成记账。
6. 足迹口径：只有 `visitedAt` 非空的条目算「去过」。
7. 天气不模拟：`WEATHER_SIMULATION` 仅供测试/离线演示；拿不到真实预报时依赖天气的修改被明确拒绝（业务码 503 含原因）。
