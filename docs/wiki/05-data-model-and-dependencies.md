# 05 · 数据模型与依赖关系

## 5.1 持久化概览

- **数据库**：默认 H2 文件库（`./data/travel-agent`，`MODE=MySQL`），`mysql` profile 切 MySQL 8.4。
- **schema 权威**：Flyway 迁移（`db/migration`），JPA `ddl-auto=validate` —— Hibernate 只校验、绝不改结构；启动缺列/类型不符直接失败。
- **方言分组**：`db/migration/`（公共 V1..V24，H2/MySQL 兼容写法）+ `db/migration-h2/`（V13/V14）+ `db/migration-mysql/`（V8/V12/V13）。同一版本号在不同 locations 按 profile 各挂一份，解决方言差异。

## 5.2 迁移时间线（V1 → V24）

| 版本 | 内容 |
| --- | --- |
| V1 | 基线（等价旧 `sql/init.sql`）：users / trip / itinerary_item / operation_history / expense / memo / schedule_item / rag_document / rag_chunk 等 |
| V2 | conversation_message（会话记忆） |
| V3 | llm_quota_usage（LLM 每日配额） |
| V4 | rag_document.fingerprint（知识库指纹） |
| V5 | rag_document.user_id（用户私有知识库） |
| V6 | pending_plan（待确认方案）+ 状态 |
| V7 | trip.version（乐观锁）、departure_reminder_dismissed_at |
| V8(mysql)/V14(h2) | llm_quota_usage 唯一约束 |
| V9 | 列表索引 + schedule_item 重复日程列 |
| V10 | rag_document_version + rag_chunk.version_id（知识库版本化） |
| V11 | rag_document.doc_key（文档键去重） |
| V12(mysql) | rag ACTIVE 版本唯一约束 |
| V13(h2/mysql 各一份) | 基线缺口补齐（存量库空转） |
| V15 | agent trace（AgentRun / AgentTask / ToolCallTrace） |
| V16 | plan_execution（计划执行记录） |
| V17 | trip.party_size（出行人数） |
| V18 | agent_task DAG（确定性任务图） |
| V19 | travel closed loop（行中→回顾闭环字段） |
| V20 | agent_command_dedupe（写命令幂等） |
| V21 | conversation scope（会话作用域） |
| V22 | schedule source_key 唯一 |
| V23 | conversation turn + meta（轮次与元数据） |
| V24 | user.token_version（令牌版本，注销/改密吊销旧 token） |

> 规则：改 schema 一律新增 `V*__` 脚本；**不改 V1 或已发布脚本**。

## 5.3 实体关系（ER 简图）

```
users 1───n trip 1───n itinerary_item        （day_index, item_type, status, visitedAt, tags[室内/室外], cost）
  │            │───n expense                 （category, amount, spent_at → actual 口径）
  │            │───n trip_photo              （PhotoStore: local / s3）
  │            │───n trip_event              （旅行事件流：打卡/计划变更）
  │            └───n operation_history       （before_state/after_state 快照 → 撤销）
  ├───n memo / schedule_item / reminder
  ├───n conversation_meta 1───n conversation_message
  ├───n rag_document 1───n rag_document_version 1───n rag_chunk   （版本化知识库 + 向量）
  ├───n llm_quota_usage                       （用户×日 计数）
  └───n pending_plan_record / plan_execution / agent_run 1───n agent_task 1───n tool_call_trace
                                                   └───n agent_command_dedupe（幂等键）
```

### 核心实体要点

| 实体 | 关键字段 | 口径 |
| --- | --- | --- |
| [Trip](../../travel-agent-server/src/main/java/com/travel/agent/entity/Trip.java) | destination, start/end_date, budget, status, version（乐观锁）, party_size | `budget` 是计划预算；`planned` 成本来自条目合计 |
| [ItineraryItem](../../travel-agent-server/src/main/java/com/travel/agent/entity/ItineraryItem.java) | day_index, item_type, start/end_time, lat/lng, cost, tags, **status**, **visitedAt** | 状态机 `PENDING`→进行中/完成/跳过；只有 `visitedAt` 非空算「去过」；重规划只动 `PENDING` |
| [OperationHistory](../../travel-agent-server/src/main/java/com/travel/agent/entity/OperationHistory.java) | operation_type, before_state, after_state, agent_reason, status(ACTIVE/UNDONE) | 撤销的依据：before/after 快照还原上一步 |
| [Expense](../../travel-agent-server/src/main/java/com/travel/agent/entity/Expense.java) | category, amount, spent_at | 记账合计 = `actual`；预算同步绝不自动生成 expense |
| [AgentRun](../../travel-agent-server/src/main/java/com/travel/agent/entity/AgentRun.java) / [AgentTask](../../travel-agent-server/src/main/java/com/travel/agent/entity/AgentTask.java) | run 状态、任务 DAG、类型（含 `DEGRADE` 降级留痕） | 运行追踪；调度是确定性任务图，模型不参与调度 |
| [PendingPlanRecord](../../travel-agent-server/src/main/java/com/travel/agent/entity/PendingPlanRecord.java) | planId, plan JSON, status | 待确认方案暂存（preview → confirm 之间） |
| [RagDocument](../../travel-agent-server/src/main/java/com/travel/agent/entity/RagDocument.java) / [RagDocumentVersion](../../travel-agent-server/src/main/java/com/travel/agent/entity/RagDocumentVersion.java) / [RagChunk](../../travel-agent-server/src/main/java/com/travel/agent/entity/RagChunk.java) | doc_key, fingerprint, ACTIVE/INACTIVE, embedding | 版本化检索：只命中 ACTIVE 版本或 version_id 为空的历史块 |
| [User](../../travel-agent-server/src/main/java/com/travel/agent/entity/User.java) | username, password(BCrypt), token_version | token_version 变更即吊销全部已签发 JWT |

## 5.4 后端内部依赖（按层）

```
controller ──► service ──► repository ──► DB
    │            ▲
    ▼            │
AgentController ─► agent.AgentEngine ─► IntentAgent/PlannerAgent/ExecutorAgent/ReflectAgent
                     │        │                │
                     │        │                ├─► tool.ToolRegistry/ToolInvoker ─► service（只读）
                     │        │                ├─► rag.RagService ─► EmbeddingService/VectorStore
                     │        │                └─► llm.DelegatingLlmClient ─► LlmClient
                     │        └─► PendingPlanStore / PlanConfirmationService
                     └─► commit 时唯一写路径：ItineraryService.applyOperations
channel.* ─────────────────► AgentEngine（同一引擎）
config.DataInitializer ─► TravelCatalogService / RagBootstrap / demo 账号
```

依赖规则：

- `agent` 可依赖 `service/tool/rag/llm/entity`；**不依赖** `controller`。
- `tool.impl` 只调 `service` 的只读方法；不注入任何可写 repository。
- `llm` 不依赖 `agent`（被 `agent`/`rag` 使用）。
- 所有跨用户数据访问必须经 service 层归属校验（`userId` 来自 `UserContext`，绝不信任客户端传值）。

## 5.5 外部依赖与降级矩阵

| 依赖 | 配置开关 | 默认 | 失败/未配置行为 |
| --- | --- | --- | --- |
| OpenAI 兼容 LLM | `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` | 未配 → 关闭 | `DelegatingLlmClient` 返回 null → 规则规划器 + 模板回复；模型探测结果自报 |
| OpenAI 兼容 Embedding | `EMBEDDING_ENABLED` | false | 用字符 bigram 哈希向量（非语义）；启用后失败进程内锁定降级回哈希 |
| 高德 Web 服务 | `AMAP_KEY` | 空 | POI/路线回落本地估算，数据标注 `source=estimated`；限流/失败自报 |
| 高德 MCP | `MCP_ENABLED` + `AMAP_MAPS_API_KEY` | false | 不注册 MCP 工具；单 server 失败只告警 |
| Open-Meteo 天气 | 无 Key（`WEATHER_SIMULATION` 仅供测试） | 真实源 | **不降级模拟**：依赖天气的修改明确拒绝（503 + 原因）；普通规划照常但如实标注未校验 |
| Redis | `RATE_LIMIT_STORE=redis` | memory | Redis 故障 fail-open（放行并告警），不拖死整站 |
| S3 兼容存储 | `PHOTOS_STORE=s3` | local | 缺 bucket/ak/sk 启动失败（显式开启即要求完整配置） |
| MySQL | `spring.profiles.active=mysql` | H2 | 仅 profile 生效；全量迁移与备份恢复已实测（docs/guide/mysql-migration.md） |
| Telegram / 微信 / Webhook | 各渠道 token/secret | 关闭 | 渠道关闭，Web 不受影响 |

## 5.6 前端 → 后端依赖

- 统一 `baseURL: '/api'`（dev 经 Vite proxy 到 :8080；prod 经 nginx 反代）。
- 鉴权：登录后存 token → `Authorization: Bearer`；SSE 与照片原图 URL 用 5 分钟只读票据（`AccessTicketController` 签发）。
- 类型契约：`docs/api/openapi.json` 是前后端唯一契约源；`npm run api:generate` 生成 TS 类型，`api:check` 是 CI 门禁。
