# 代码结构手册（Code Structure）

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.7 / Node 20） | 最后验证：2026-11-19 | 事实来源：travel-agent-server/src/main/java/com/travel/agent/、travel-agent-web/src/

> 目的：让「新功能该放哪个包、哪个类」有唯一答案，让新接手的同学 10 分钟建立代码地图。
> 本手册随「代码框架整理」引入，与源码同步维护；与代码不一致时**以代码为准**，并在同一次改动里修文档。

---

## 1. 顶层目录

```
travel-agent/
├── travel-agent-server/          # 后端：Spring Boot 3 / Java 17
│   ├── src/main/java/com/travel/agent/   # 业务代码（222 个文件，16 个包）
│   ├── src/main/resources/                # application*.yml、RAG 知识库、Flyway 迁移
│   ├── src/test/java/com/travel/agent/    # JUnit + MockMvc（110 个文件，内存 H2）
│   ├── data/                              # 本地 H2 库 + 联网城市缓存（gitignore）
│   └── run-dev.ps1 / run-dev.cmd          # 本地启动脚本（注入 .env.local）
├── travel-agent-web/             # 前端：React 18 + TypeScript + Vite
│   ├── src/{pages,components,api,store,types,lib,hooks,styles,test}
│   └── e2e/                      # Playwright 冒烟
├── docs/                         # 文档套件（guide / proposals / review / design）
├── sql/init.sql                  # 历史初始化脚本（已弃用，以 Flyway 为准）
├── .e2e/ .docgen/ .tools/        # 手工验证脚本 / 文档校验脚本 / 本地探针（.tools 不入库）
└── docker-compose*.yml           # 全栈 / 本地基础设施
```

---

## 2. 后端分层与依赖方向

```
HTTP / SSE / MCP / 渠道
        │
        ▼
controller · channel · mcp          ← 只做协议适配，不写业务规则
        │
        ▼
agent（状态机 + 任务图 + Intent/Planner/Executor/Reflect + 确认编排）
        │
        ├──────────────┬──────────────┐
        ▼              ▼              ▼
   service(领域)     tool(只读)     rag / llm
        │              │
        └──────┬───────┘
               ▼
       model · util · dto
               │
               ▼
       repository · entity（JPA）
```

依赖规则（评审时可据此判断是否放错层）：

| 规则 | 说明 |
| --- | --- |
| controller 不碰 repository | 只依赖 `service` / `agent` / `dto`，直接查库视为越层 |
| `agent` 可依赖 `service` / `tool` / `rag` / `llm` | 但 `service` 不反向依赖 `agent`（`PlanNarrationService` 由引擎调用，自身无 agent 依赖） |
| `tool` 只读 | `ToolRegistry` 拒绝 `readOnly()==false` 的工具；写操作必须表达为 `AgentPlan` |
| `tool/impl` 可回调 `service` | 如 `OnlineCitySearchTool → OnlineCityService`；`service.AmapDataService` 用 `ObjectProvider<ToolRegistry>` 懒取工具，避免构造环 |
| `entity` / `repository` 不依赖上层 | `dto` 只被 `controller` / `agent` 使用 |
| `config` / `security` 横切 | `security` 的 Filter 只依赖 `security` / `config`；`config` 里的 `*Properties` 不依赖业务类 |

---

## 3. 包职责（后端 222 个主代码文件）

| 包 | 文件数 | 职责 | 关键类 |
| --- | --- | --- | --- |
| `agent` | 29 | Agent 引擎：状态机、四个 Agent、任务图调度、上下文、计划、会话记忆、待确认方案 | `AgentEngine`、`Coordinator`、`PlanReviewLoop`、`PlannerAgent`、`StructuredPlanner`、`PlanConfirmationService` |
| `controller` | 12 | REST API + SSE 流式接口 | `AgentController`、`TripController`、`TravelKnowledgeController`、`SystemController` |
| `service` | 36 | 行程 / 预算 / 操作历史 / 目录 / 天气 / 路线 / 回顾 / 事件 / 提醒 / 路书 / 存储 | `TripService`、`ItineraryService`、`TripReviewService`、`TripEventService`、`OnlineCityService` |
| `tool` | 14 | `AgentTool` 契约、只读注册表、风险分级、调用入口、9 个内置只读工具 | `ToolRegistry`、`ToolInvoker`、`SafetyInterceptor`、`QueryTravelKnowledgeTool` |
| `rag` | 15 | Embedding（本地哈希 / OpenAI 兼容）、向量库、检索、引用、知识库引导与版本化 | `RagService`、`RagCitation`、`InMemoryVectorStore`、`RagBootstrap` |
| `repository` | 19 | Spring Data JPA 仓储 | — |
| `entity` | 19 | JPA 实体（表结构以 Flyway 迁移为准） | `Trip`、`ItineraryItem`、`TripEvent`、`AgentTask` |
| `mcp` | 7 | MCP 外部工具桥接（streamable HTTP / 本机 stdio） | `McpToolBridge`、`StdioMcpTransport` |
| `config` | 14 | 配置属性、安全/异步/时区/追踪、引导数据 | `AmapProperties`、`SecurityConfig`、`DataInitializer` |
| `security` | 11 | JWT、Cookie、访问票据、限流、用户上下文 | `JwtService`、`RateLimitFilter`、`AccessTicketService` |
| `dto` | 12 | 传输对象（请求/响应），按业务模块聚合 | `AgentDtos`、`AgentTraceDtos`、`TripDtos`、`TravelReviewDtos`、`ApiResponse` |
| `model` | 8 | 领域模型（不落库的只读数据） | `CityCatalog`、`Attraction`、`WeatherInfo` |
| `channel` | 7 | 多渠道入口：Telegram / 微信公众号 / 通用 Webhook | `TelegramBotService`、`WebhookChannelController` |
| `llm` | 7 | 统一 LLM 接口（OpenAI-compatible）+ 规则实现 + 委派降级 | `LlmClient`、`DelegatingLlmClient` |
| `util` | 8 | 通用工具（城市名、坐标、日期、分页、重复规则） | `CityNames`、`CoordinateTransform`、`Paging` |
| `exception` | 3 | 业务异常 + 任务依赖异常 + 全局异常处理 | `BusinessException`、`TaskDependencyException`、`GlobalExceptionHandler` |

---

## 4. 关键协作类（为什么这样拆）

### 4.1 Agent 引擎

`AgentEngine` 只保留**状态机与编排**（856 行）：取消边界、会话记忆、规划 → 并行预览/审核 → 待确认链路、任务图节点派发（经 `Coordinator`）、外部工具调用、确认接口的委托入口。具体职责拆到：

| 类 | 行数 | 职责 |
| --- | --- | --- |
| `AssistantIntentHandler` | 139 | 备忘 / 日程的答复与直写（日报/健康入口已删除） |
| `TravelQueryHandler` | 142 | 知识问答（RAG）/ 预算 / 记账 |
| `PlanConfirmationService` | 271 | 确认 / 取消 / 取回 / 撤销（Human-in-the-loop 并发语义） |
| `AgentStageTimer` | 53 | `agent.stage{stage=intent\|plan\|tool\|reflect\|commit}` 计时 |
| `Coordinator` | 382 | 任务图执行：依赖校验、并行组、回收重派、CONFLICT |
| `PlanReviewLoop` | 148 | 审核 → 有限重规划 → 再审闭环（派发 REPLAN / 复审） |
| `PlannerAgent` | 851 | 规划规则与结构化规划入口（新行程 / 行程修改 / 行中锁定 / RAG 引用） |
| `PlannerDraftSupport` | 128 | 候选选择（景点/餐厅/替换） + 条目草稿 + 打车成本（两条路径共用口径） |

> 拆分原则：**新增协作类、不改公开签名**。`AgentEngine.confirm/cancel/pendingPreview/undo` 保留为委托入口，
> 既有调用方与测试（`AgentStageMetricsTest`、`PendingPlanRevisionTest` 等）无需改动。

### 4.2 联网城市数据

`OnlineCityService`（243 行）只做**编排与缓存决策**（内存 24h / 文件 7 天），能力委托给：

| 类 | 行数 | 职责 |
| --- | --- | --- |
| `CityGeoLocator` | 102 | 城市坐标定位：高德优先、Nominatim 兜底 |
| `AmapGeocodeClient` | 221 | 高德地理编码（单点 / 批量 / 城市校验），包内协作类，不注册 Bean |
| `OnlineCityCatalogReader` | 254 | 两套提示词 + 模型 JSON → `CityCatalog`（默认值与脏数据过滤） |
| `CityCatalogDiskCache` | 82 | `data/online-cities/<城市>.json` 文件缓存（7 天） |

### 4.3 高德配置与坐标解析

- **Key 读取唯一入口**：`config.AmapProperties#usable()`。禁止再写 `@Value("${travel.amap.key:}")`。
  注意两个凭据不同：`travel.amap.key`（`AMAP_KEY`，Web 服务）与 MCP 子进程的 `AMAP_MAPS_API_KEY`。
- **高德坐标解析唯一实现**：`util.CoordinateTransform#parseGcj02Location`（`"经度,纬度"` GCJ-02 → WGS-84，零坐标返回 `null`）。
  地理编码、POI 搜索都复用它，不要再各写一份 `parseLocation`。

---

## 5. 我要加 X → 放哪里

| 需求 | 落点 | 注意 |
| --- | --- | --- |
| 新 REST 接口 | `controller/XxxController` + `service/XxxService` + `dto/XxxDtos` | 响应统一 `ApiResponse`；鉴权走 `userId`/票据边界 |
| 新 Agent 意图 | `IntentResult` 常量 → `IntentAgent`（LLM 提示 + 规则兜底）→ `AgentEngine` 路由 → 对应 Handler | 会落库的意图要同步票据只读拦截集合 `TICKET_READONLY_BLOCKED_INTENTS` |
| 新任务图节点 | 引擎/审核闭环的调用点用 `Coordinator.dispatch` / `dispatchAll` 声明 `TaskSpec` | 依赖写死、运行期不改图、模型不参与调度（ADR-011） |
| 新只读工具 | `tool/impl/XxxTool implements AgentTool`，在 `ToolRegistry` 注册 | `readOnly=true`；写操作必须走 `AgentPlan` + 确认 |
| 新外部数据源 | `service/Xxx...Service`（REST）或 `mcp/`（MCP 通道） | 一律 fail-soft，失败降级不阻断规划 |
| 新配置项 | `config/XxxProperties` + `application.yml`（环境变量占位） | 不新增散落的 `@Value` |
| 新实体/表 | `entity/` + `repository/` + `db/migration*/V*.sql` | 只新增迁移，不改已应用脚本（Flyway checksum） |
| 新前端页面 | `pages/Xxx/index.tsx` + `App.tsx` 路由 + `api/` + `types/` | 懒加载 + `Protected` 包裹 |

---

## 6. 命名约定

| 后缀 | 语义 | 示例 |
| --- | --- | --- |
| `Engine` / `Agent` | 编排与决策（有状态机语义） | `AgentEngine`、`PlannerAgent` |
| `Handler` | 从编排类抽出的「一类意图/请求」处理器 | `AssistantIntentHandler` |
| `Service` | 领域能力（可被 controller / agent / tool 复用） | `TripService`、`ExpenseService` |
| `Tool` | 只读能力，注册进 `ToolRegistry` | `SearchHotelTool` |
| `Store` | 持久化抽象（本地/S3 双实现） | `PhotoStore`、`S3PhotoStore` |
| `Client` / `Transport` | 外部协议/HTTP 细节 | `AmapGeocodeClient`、`StdioMcpTransport` |
| `Reader` / `Cache` | 解析、缓存等单一职责协作类 | `OnlineCityCatalogReader`、`CityCatalogDiskCache` |
| `Properties` | `@ConfigurationProperties` 配置对象 | `AmapProperties` |
| `Dtos` | 一个业务模块的 DTO 聚合容器 | `AgentDtos`、`TripDtos` |
| `Filter` / `Interceptor` | Web 横切（认证、限流、TraceId） | `JwtAuthenticationFilter` |
| `Job` / `Config` | 定时任务 / Spring 装配 | `RagChunkRetentionJob`、`AsyncConfig` |

---

## 7. 测试组织

- 后端：`src/test/java` 按被测包镜像；集成测试继承 `support/IntegrationTestSupport`（内存 H2、离线、演示账号）。
- 纯单元测试（无 Spring 上下文）放同包，命名 `XxxTest`；涉及 HTTP/事务/并发的用 `@SpringBootTest` + `MockMvc`。
- 前端：vitest 与被测文件同目录，`*.test.ts(x)`；e2e 在 `travel-agent-web/e2e/`（Playwright）。
- 手工端到端脚本：`.e2e/*.mjs`（需先启动后端，`docs/DEVELOPMENT.md` 有清单）。

---

## 8. 维护红线

1. **单类超过 500 行或构造函数超过 12 个依赖** → 拆内聚协作类（先例：`AgentEngine` 779 → 361、`OnlineCityService` 648 → 205）。
2. **一个类只做一件事**：编排类不写 JSON 解析/网络细节；解析类不写缓存；缓存类不写业务判定。
3. **拆分时优先新增类，不改公开方法签名**，保证调用方与测试契约稳定。
4. **不重复实现公共规则**：城市名匹配用 `util.CityNames`、高德坐标解析用 `CoordinateTransform.parseGcj02Location`、
   Key 判断用 `*Properties#usable()`。
5. **写操作唯一入口**：`ExecutorAgent.commit`（事务化 + 操作历史）；工具层永远只读。
6. **配置外置**：Key/密码只从环境变量或 `.env.local` 注入，仓库不落任何密钥。

---

## 9. 本次整理变更清单（第十一批）

> 下表行数是第十一批整理当时的快照；当前行数见 §4（P1 后 `AgentEngine` 856 行、`PlannerAgent` 851 行、`OnlineCityService` 243 行）。
> P0 产品收缩后，`AssistantIntentHandler` 只保留备忘/日程，日报/健康分支已删除；学习/办公/健康 Controller 与服务不再存在。

| 项 | 之前 | 之后 |
| --- | --- | --- |
| `AgentEngine` | 779 行、23 个依赖、内联 4 类意图答复 | 361 行；拆出 `AssistantIntentHandler` / `TravelQueryHandler` / `PlanConfirmationService` / `AgentStageTimer` |
| `PlannerAgent` | 692 行、内联候选选择与草稿构造 | 538 行；候选/草稿抽到 `PlannerDraftSupport`（两条规划路径共用口径） |
| `OnlineCityService` | 648 行、地理编码/提示词/文件缓存全内联 | 205 行；拆出 `CityGeoLocator` / `AmapGeocodeClient` / `OnlineCityCatalogReader` / `CityCatalogDiskCache` |
| 高德 Key 读取 | 3 处 `@Value("${travel.amap.key:}")` | 统一 `AmapProperties#usable()` |
| 高德坐标解析 | 2 份重复 `parseLocation` | 统一 `CoordinateTransform#parseGcj02Location` |
| 未使用代码 | 13 个未使用 import、`PlannerAgent.hhmm`、无引用的 `AssistantNav.tsx` | 已清理 |
| 仓库卫生 | 根目录 4 个构建日志、根 `data/`、`.tools/` 噪音 | 清理并补 `.gitignore` |