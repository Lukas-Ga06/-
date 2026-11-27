# 02 · 后端模块（travel-agent-server）

> 根包：`com.travel.agent`，入口 [TravelAgentApplication](../../travel-agent-server/src/main/java/com/travel/agent/TravelAgentApplication.java)。
> 本章按包列出职责与关键类；Agent 引擎细节见 [03 · Agent 引擎](03-agent-engine.md)。

## 2.1 包职责总览

| 包 | 职责 | 关键类数 |
| --- | --- | --- |
| `agent` | Agent 引擎：状态机编排、四类 Agent、计划结构与确认流程 | 24 |
| `tool` / `tool.impl` | 只读工具抽象、注册表、安全拦截；9 个内置工具 | 15 |
| `rag` | 检索增强：向量存储、Embedding、版本化知识库、引用生成 | 14 |
| `llm` | OpenAI 兼容客户端、模型探测、规则降级客户端 | 8 |
| `mcp` | MCP 协议桥接（stdio/http transport），外部工具注册 | 7 |
| `channel` | Telegram / 微信公众号 / Webhook 多渠道适配 | 7 |
| `controller` | REST 端点（12 个 Controller） | 12 |
| `service` | 业务服务层（行程/预算/记账/照片/回顾/账户等） | 40+ |
| `entity` / `repository` | JPA 实体（22 个）与 Spring Data 仓库 | 22 + 20 |
| `security` | JWT、登录 Cookie、访问票据、限流计数 | 11 |
| `config` | Spring 配置、属性绑定、启动初始化 | 16 |
| `dto` | 请求/响应 DTO 与统一信封 | 14 |
| `model` | 领域值对象（城市目录/POI/路线/天气） | 9 |
| `observability` | 业务指标 | 1 |
| `exception` | 业务异常与全局处理 | 3 |
| `util` | 纯函数工具（日期/坐标/分页/曲线简化等） | 9 |

## 2.2 controller — REST 端点分组

统一前缀 `/api`，返回统一信封 `ApiResponse{code,message,data}`。

| Controller | 端点域 | 说明 |
| --- | --- | --- |
| [AgentController](../../travel-agent-server/src/main/java/com/travel/agent/controller/AgentController.java) | `/api/agent/**` | 对话（同步 + SSE 流式）、计划确认/取消、运行记录、撤销 |
| [AuthController](../../travel-agent-server/src/main/java/com/travel/agent/controller/AuthController.java) | `/api/auth/**` | 注册/登录/登出、`/me` 会话恢复、注销（`DELETE /me`）、数据导出（`GET /me/export`） |
| [TripController](../../travel-agent-server/src/main/java/com/travel/agent/controller/TripController.java) | `/api/trips/**` | 行程 CRUD、回顾、路书导出、地图数据 |
| [ItineraryController](../../travel-agent-server/src/main/java/com/travel/agent/controller/ItineraryController.java) | `/api/itinerary/**` | 行程条目状态推进（开始/完成/跳过）、手动编辑 |
| [ExpenseController](../../travel-agent-server/src/main/java/com/travel/agent/controller/ExpenseController.java) | `/api/expenses/**` | 记账 CRUD、预算执行 |
| [MemoController](../../travel-agent-server/src/main/java/com/travel/agent/controller/MemoController.java) | `/api/memos/**` | 备忘 |
| [ScheduleController](../../travel-agent-server/src/main/java/com/travel/agent/controller/ScheduleController.java) | `/api/schedule/**` | 日程 |
| [ReminderController](../../travel-agent-server/src/main/java/com/travel/agent/controller/ReminderController.java) | `/api/reminders/**` | 提醒 |
| [PhotoController](../../travel-agent-server/src/main/java/com/travel/agent/controller/PhotoController.java) | `/api/photos/**` | 行程照片上传/读取（票据鉴权） |
| [TravelKnowledgeController](../../travel-agent-server/src/main/java/com/travel/agent/controller/TravelKnowledgeController.java) | `/api/knowledge/**` | 知识库检索、问答、文档版本管理 |
| [SystemController](../../travel-agent-server/src/main/java/com/travel/agent/controller/SystemController.java) | `/api/system/**` | 系统状态（LLM 可用性、天气 simulated 自报、能力边界） |
| [AccessTicketController](../../travel-agent-server/src/main/java/com/travel/agent/controller/AccessTicketController.java) | `/api/tickets/**` | 短期只读票据签发（SSE/照片 URL 用，默认 5 分钟） |

另有 `channel/WebhookChannelController`（`/api/channels/webhook`）与 `channel/WeChatOfficialAccountController`（`/api/channels/wechat`）两个渠道端点。

## 2.3 service — 业务服务分组

**行程核心**
- [TripService](../../travel-agent-server/src/main/java/com/travel/agent/service/TripService.java)：行程 CRUD 与归属校验。
- [ItineraryService](../../travel-agent-server/src/main/java/com/travel/agent/service/ItineraryService.java)：`applyOperations()` 是 Agent 写路径的唯一落库入口；条目状态机（`PENDING`→进行中/完成/跳过）与 `visitedAt` 口径。
- [TripEventService](../../travel-agent-server/src/main/java/com/travel/agent/service/TripEventService.java)：旅行事件流（打卡/计划变更）。
- [TripReviewService](../../travel-agent-server/src/main/java/com/travel/agent/service/TripReviewService.java)：行后回顾（完成率、计划 vs 实际、AI 总结）。
- [TripMapService](../../travel-agent-server/src/main/java/com/travel/agent/service/TripMapService.java) / [TripGuidebookService](../../travel-agent-server/src/main/java/com/travel/agent/service/TripGuidebookService.java)：地图数据聚合、路书 PDF。

**预算与记账**
- [BudgetService](../../travel-agent-server/src/main/java/com/travel/agent/service/BudgetService.java)：预算测算与同步（只记快照）。
- [BudgetExecutionService](../../travel-agent-server/src/main/java/com/travel/agent/service/BudgetExecutionService.java)：`planned`（计划成本）vs `actual`（记账合计）执行视图。
- [ExpenseService](../../travel-agent-server/src/main/java/com/travel/agent/service/ExpenseService.java)：记账。
- [PriceReferenceService](../../travel-agent-server/src/main/java/com/travel/agent/service/PriceReferenceService.java)：参考估算价（非实时库存）。

**外部数据**
- [WeatherService](../../travel-agent-server/src/main/java/com/travel/agent/service/WeatherService.java)：Open-Meteo 真实预报；失败负缓存；`simulation=true` 仅供测试。
- [AmapDataService](../../travel-agent-server/src/main/java/com/travel/agent/service/AmapDataService.java) / [AmapGeocodeClient](../../travel-agent-server/src/main/java/com/travel/agent/service/AmapGeocodeClient.java)：高德 POI/地理编码，`source=amap|estimated` 标注。
- [RouteService](../../travel-agent-server/src/main/java/com/travel/agent/service/RouteService.java) / [ItineraryRouteService](../../travel-agent-server/src/main/java/com/travel/agent/service/ItineraryRouteService.java)：路线（真实或本地估算）。
- [OnlineCityService](../../travel-agent-server/src/main/java/com/travel/agent/service/OnlineCityService.java) / [OnlineCityCatalogReader](../../travel-agent-server/src/main/java/com/travel/agent/service/OnlineCityCatalogReader.java) / [CityAtlas](../../travel-agent-server/src/main/java/com/travel/agent/service/CityAtlas.java) / [CityCatalogDiskCache](../../travel-agent-server/src/main/java/com/travel/agent/service/CityCatalogDiskCache.java) / [CityGeoLocator](../../travel-agent-server/src/main/java/com/travel/agent/service/CityGeoLocator.java)：内置城市目录 + 在线城市数据 + 磁盘缓存。
- [TravelCatalogService](../../travel-agent-server/src/main/java/com/travel/agent/service/TravelCatalogService.java)：加载 `resources/data/travel-catalog.json` 内置目录。

**计划执行与历史**
- [OperationHistoryService](../../travel-agent-server/src/main/java/com/travel/agent/service/OperationHistoryService.java)：before/after 快照，支撑撤销。
- [PlanExecutionService](../../travel-agent-server/src/main/java/com/travel/agent/service/PlanExecutionService.java) / [PlanScheduleSyncWriter](../../travel-agent-server/src/main/java/com/travel/agent/service/PlanScheduleSyncWriter.java) / [PlanNarrationService](../../travel-agent-server/src/main/java/com/travel/agent/service/PlanNarrationService.java)：执行记录、计划→日程同步、方案文案。
- [AgentTraceService](../../travel-agent-server/src/main/java/com/travel/agent/service/AgentTraceService.java) / [AgentTraceRetentionJob](../../travel-agent-server/src/main/java/com/travel/agent/service/AgentTraceRetentionJob.java)：运行追踪与留存清理。
- [AgentCommandIdempotency](../../travel-agent-server/src/main/java/com/travel/agent/service/AgentCommandIdempotency.java)：写命令幂等去重。

**会话与账户**
- [ConversationService](../../travel-agent-server/src/main/java/com/travel/agent/service/ConversationService.java) / [ConversationTurnWriter](../../travel-agent-server/src/main/java/com/travel/agent/service/ConversationTurnWriter.java)：会话记忆（最近 N 条供意图识别，保留期可配）。
- [AuthService](../../travel-agent-server/src/main/java/com/travel/agent/service/AuthService.java) / [AccountDeletionService](../../travel-agent-server/src/main/java/com/travel/agent/service/AccountDeletionService.java) / [AccountExportService](../../travel-agent-server/src/main/java/com/travel/agent/service/AccountExportService.java)。
- [LlmQuotaService](../../travel-agent-server/src/main/java/com/travel/agent/service/LlmQuotaService.java)：每日 LLM 配额。

**照片 / 备忘 / 日程 / 提醒**
- [PhotoService](../../travel-agent-server/src/main/java/com/travel/agent/service/PhotoService.java) + `PhotoStore` 接口（[LocalPhotoStore](../../travel-agent-server/src/main/java/com/travel/agent/service/LocalPhotoStore.java) 默认 / [S3PhotoStore](../../travel-agent-server/src/main/java/com/travel/agent/service/S3PhotoStore.java) 可选），单行程 200 张、单用户 500MB 上限。
- [MemoService](../../travel-agent-server/src/main/java/com/travel/agent/service/MemoService.java) / [ScheduleService](../../travel-agent-server/src/main/java/com/travel/agent/service/ScheduleService.java) / [ReminderService](../../travel-agent-server/src/main/java/com/travel/agent/service/ReminderService.java)。

## 2.4 security — 认证与限流

- [JwtService](../../travel-agent-server/src/main/java/com/travel/agent/security/JwtService.java)：Base64 密钥（≥32 字节）签发/校验；未配置时启动生成一次性随机密钥（重启全体掉线，仅适合本地）。
- [JwtAuthenticationFilter](../../travel-agent-server/src/main/java/com/travel/agent/security/JwtAuthenticationFilter.java)：Bearer / Cookie 双通道，写入 `UserContext`。
- [AuthCookieService](../../travel-agent-server/src/main/java/com/travel/agent/security/AuthCookieService.java)：登录 Cookie（`COOKIE_SECURE` 控制 Secure 位）。
- [AccessTicketService](../../travel-agent-server/src/main/java/com/travel/agent/security/AccessTicketService.java)：短期只读票据，仅 SSE 与照片原图两个白名单路径接受。
- [RateLimitFilter](../../travel-agent-server/src/main/java/com/travel/agent/security/RateLimitFilter.java) + `RateCounter`（[InMemoryRateCounter](../../travel-agent-server/src/main/java/com/travel/agent/security/InMemoryRateCounter.java) / [RedisRateCounter](../../travel-agent-server/src/main/java/com/travel/agent/security/RedisRateCounter.java)）：分桶限流，Redis 故障 fail-open。
- [TrustedProxies](../../travel-agent-server/src/main/java/com/travel/agent/security/TrustedProxies.java)：默认不信任任何代理、不读 `X-Forwarded-For`。

## 2.5 config — 关键配置类

- [SecurityConfig](../../travel-agent-server/src/main/java/com/travel/agent/config/SecurityConfig.java)：安全过滤链与白名单（actuator 端点需登录）。
- [SecurityStartupGuard](../../travel-agent-server/src/main/java/com/travel/agent/config/SecurityStartupGuard.java)：启动期安全检查（如生产环境弱配置告警）。
- [DataInitializer](../../travel-agent-server/src/main/java/com/travel/agent/config/DataInitializer.java)：首启创建 demo 账号、加载城市目录与知识库。
- [TimeZoneConfig](../../travel-agent-server/src/main/java/com/travel/agent/config/TimeZoneConfig.java) / [TraceIdFilter](../../travel-agent-server/src/main/java/com/travel/agent/config/TraceIdFilter.java) / [AsyncConfig](../../travel-agent-server/src/main/java/com/travel/agent/config/AsyncConfig.java) / [OpenApiConfig](../../travel-agent-server/src/main/java/com/travel/agent/config/OpenApiConfig.java)。
- 属性绑定：`AgentBudgetProperties` / `AmapProperties` / `ChannelProperties` / `McpProperties` / `RateLimitProperties` / `S3Properties` / `WeatherProperties`。

## 2.6 exception / dto / model / util

- [GlobalExceptionHandler](../../travel-agent-server/src/main/java/com/travel/agent/exception/GlobalExceptionHandler.java)：`BusinessException` → 业务码；[TaskDependencyException](../../travel-agent-server/src/main/java/com/travel/agent/exception/TaskDependencyException.java) 用于 Agent 任务图依赖失败。
- `dto`：`ApiResponse` 统一信封 + 按域聚合的 `*Dtos`（Agent/Auth/Budget/Chat/Expense/Export/Itinerary/Photo/Reminder/TravelReview/Trip/AgentTrace）。
- `model`：非持久化值对象 —— `CityCatalog`、`Attraction`、`Hotel`、`Restaurant`、`RouteInfo`、`WeatherInfo`、`WeatherLookup`、`AmapPoi`、`AmapRoute`。
- `util`：`Dates`（应用时区日期）、`CoordinateTransform`（GCJ-02/WGS-84）、`PolylineSimplify`（路线抽稀）、`Recurrence`（重复日程）、`Paging`、`CityNames`、`ImageSignatures`、`ErrorSummary`、`ExternalDegradationMetrics`。

## 2.7 channel / mcp — 扩展入口

- `channel`：`TelegramBotService`（长轮询）+ `TelegramClient`、`WeChatOfficialAccountController` + `WeChatClient` + `WeChatChannelConfig`、`WebhookChannelController`、`ChannelAccounts`（渠道账号与站内用户映射）。全部复用同一 `AgentEngine`。
- `mcp`：`McpToolBridge` 在启动时把外部 MCP server 的只读工具注册进 `ToolRegistry`（命名 `mcp_<server>_<tool>`）；`McpConnection` 管理连接，`StdioMcpTransport`/`HttpMcpTransport` 两种传输；`AmapMcpAdapter` 适配高德官方 MCP server。单 server 失败只告警不影响启动。
