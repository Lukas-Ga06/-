# AI 个人助理（旅行规划 · 记账 · 备忘 · 办公 · 学习 · 健康）

以「行前规划 + 行中动态调整」为主线的个人助理系统，同时覆盖记账、备忘、日程、办公自动化（邮件/纪要/日报/发票）、学习辅助与健康数据。

**技术路线**：Spring Boot 3 + Agent Engine（Intent / Planner / Executor / Reflect）+ RAG + Tools + React 18
**核心交互**：Voice/Text + Map + Timeline + Card
**核心闭环**：自然语言表达意图 → Agent 理解与规划 → Tools 执行 → RAG 提供知识 → GUI 可视化 → 用户确认 → 行程更新

> 仓库目录沿用 `travel-agent`，前端标题为「AI 个人助理」：旅行是核心场景，其余模块复用同一套 Agent 引擎与权限体系。

---

## 1. 项目结构

```
travel-agent/
├── sql/init.sql                        # MySQL 初始化脚本（H2 模式无需执行）
├── docker-compose.yml                  # 可选：MySQL + Redis 基础设施
├── .github/workflows/ci.yml            # CI：后端 mvn verify + 前端 typecheck/test/build
├── docs/DEVELOPMENT.md                 # 环境变量、测试、约定、部署与后续规划
├── docs/CHANGELOG.md                   # 行为变化记录（含影响使用习惯的调整）
├── .e2e/                               # 手工端到端验证脚本（需本地先启动后端）
├── travel-agent-server/                # 后端（Spring Boot 3 / Java 17）
│   ├── run-dev.ps1                     # 本地启动脚本：读取 .env.local 注入密钥后启动
│   ├── .env.local                      # 本地密钥（gitignore，不入库）
│   ├── src/main/java/com/travel/agent/
│   │   ├── agent/                      # Agent Engine：状态机 + 四个 Agent + 上下文 + 计划
│   │   ├── tool/                       # AgentTool 接口 / ToolRegistry / SafetyInterceptor / 8 个只读工具
│   │   ├── rag/                        # Embedding / VectorStore / RagService / 知识库引导
│   │   ├── llm/                        # 统一 LLM 接口（OpenAI-compatible，可切 DeepSeek/Qwen）
│   │   ├── controller/                 # REST API + SSE 流式接口
│   │   ├── service/                    # 行程/预算/操作历史/目录/天气/路线/办公/学习/健康
│   │   ├── entity/ repository/         # JPA 实体与数据访问
│   │   ├── security/ config/           # JWT 认证、安全配置、CORS
│   │   ├── util/                       # 通用工具（日期解析等）
│   │   └── exception/                  # 全局异常处理
│   └── src/test/                       # JUnit + MockMvc 集成测试（内存 H2，不触网）
└── travel-agent-web/                   # 前端（React 18 + TypeScript + Vite + AntD + ECharts + Leaflet/MapLibre）
    ├── vitest.config.ts                # 前端单元测试配置
    └── src/
        ├── components/                 # ChatPanel / MapView(MapProvider) / Timeline / ItineraryCard / BudgetChart / PlanDiff / VoiceButton
        ├── pages/                      # Home / Planner / Trip / Travel / Footprint(3D 足迹) / Workbench(办公+学习) / Expenses / Memos / Schedule / Office / Learning / Health / Assistant / Profile / Login
        ├── store/                      # zustand：auth / trip / agent / ui
        ├── api/                        # axios 封装 + SSE EventSource 客户端
        └── types/                      # 与后端 AgentResult / PlanDiff 对齐的 TS 类型
```

## 2. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | 后端编译运行 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+（CI 使用 20） | 前端构建与测试（含 npm） |

数据库：**默认使用 H2 文件数据库，开箱即用，无需安装任何数据库**。
要切换 MySQL/Redis 时运行 `docker compose up -d` 并按第 6 节修改配置。

> ⚠️ 仓库内**不保存任何密钥**：`JWT_SECRET` 与 `LLM_API_KEY` 一律通过环境变量注入，
> 未配置时的降级行为见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## 3. 快速启动

### 3.1 启动后端（端口 8080）

```bash
cd travel-agent-server

# 可选但推荐：固定 JWT 密钥
#   不设置时每次启动生成一次性随机密钥 —— 开箱即用，但重启后需重新登录
export JWT_SECRET="$(openssl rand -base64 48)"     # Windows PowerShell: $env:JWT_SECRET = "<base64>"

# 可选：接入真实大模型；不设置则全部走规则引擎，功能完整可用
export LLM_API_KEY="sk-你的key"

mvn spring-boot:run
```

> 更省事的方式（推荐）：把密钥写进 `travel-agent-server/.env.local`（该文件已被 gitignore），
> 然后 `.\run-dev.ps1` 启动——脚本会逐条注入环境变量，并在首次运行时自动生成并固定 `JWT_SECRET`。

首次启动会自动完成：
- 建表（JPA ddl-auto）
- 创建演示账号 **demo / demo123**
- 加载旅行目录数据（上海/北京/杭州/石家庄）与 RAG 旅游知识库（10 篇文档 → 切块 → Embedding → 向量库）

### 3.2 启动前端（端口 5173）

```bash
cd travel-agent-web
npm install
npm run dev
```

打开 http://localhost:5173 ，使用 **demo / demo123** 登录。

### 3.3 演示流程（MVP 核心闭环）

1. **首页**输入：「帮我安排一个上海三日游，预算3000元，酒店离地铁近一点」→ 进入规划页；
2. 聊天面板实时展示 Agent 过程（意图识别 → 调用工具 → 生成方案），完成后弹出 **Plan Diff 确认弹窗**（原方案 → 新方案、预算对比）；
3. 点击「确认修改」→ 行程落库，自动跳转 **行程主界面**（左地图 / 右 AI 助手 / 下时间轴）；
4. **行中调整**：在行程页对 AI 说「明天下雨，把户外景点换掉」→ Agent 调用天气工具（模拟降雨）→ 把室外景点替换为就近室内场馆 → Diff 确认后更新地图与时间轴；
5. **多模态指代**：点选时间轴上某个安排（如博物馆），再说「这个换成类似的」→ Agent 通过 selectedElement 上下文准确定位目标；
6. **撤销**：点击右上角撤销按钮，基于操作历史 before/after 快照还原上一步；
7. **预算/知识问答**：「现在花了多少钱？」「上海有什么适合亲子游的地方？」→ 预算服务 / RAG 检索回答。

同一套对话入口还能完成：记账（「记一笔午餐 45 元」）、备忘（「记一下明天办签证」）、日程（「明天下午3点开评审会」）、日报（「生成今日日报」）、健康查询（「我最近睡得怎么样」）。

## 4. 核心设计

### 4.1 Agent 状态机

```
RECEIVE → UNDERSTAND → PLAN → EXECUTE → REFLECT → PRESENT → CONFIRM → COMMIT
                                      ↘ ERROR（反馈用户）
```

- **IntentAgent**：LLM 优先 + 规则兜底，输出 `{intent, target, constraints}`（规则边界见 4.7）；
- **PlannerAgent**：结合行程/预算/偏好/天气/交通生成 `AgentPlan`（操作列表，不直接落库）；
- **ExecutorAgent**：preview（生成 Diff + 确认请求）→ 用户确认 → commit（单事务内 Service → Repository + 操作历史）；
- **ReflectAgent**：基于「应用计划之后」的行程做时间冲突检查（覆盖 ADD 与 MODIFY 造成的重叠）与预算超限校验，输出警告。

### 4.2 Human-in-the-loop 三级风险

风险等级**只用于方案级判定**（`SafetyInterceptor.evaluatePlanChange`），由 Agent 写成 `confirmation.riskLevel`，前端据此决定确认框的样式：

| 等级 | 判定条件 | 策略 |
| --- | --- | --- |
| LOW | 只读操作（查询、推荐） | 自动执行 |
| MEDIUM | 会改变行程 | 生成 Diff，确认后提交 |
| HIGH | 变更后超出行程预算 | Diff + 醒目警告，确认后提交 |

工具层（AgentTool）**只允许只读操作**：`ToolRegistry` 会拒绝注册任何 `readOnly()==false` 的工具，
因此不存在"工具绕过确认直接改库"的路径——所有写入必须表达为 AgentPlan 的操作项。

### 4.3 静态知识 / 实时信息分离

- **RAG**：旅游知识、景点介绍、城市攻略、注意事项（内置 10 篇文档，内存向量库 Top-K 检索）。
  检索按目的地城市过滤，但**标记为「通用」或未标注城市的文档始终参与**，因此"注意事项"这类通用知识在任何行程里都查得到；
- **Tools**：天气 / 路线 / 酒店价格等实时信息（MVP 为确定性模拟数据，接口与真实服务一致）。

> 当前 Embedding 为本地字符 bigram 哈希（`LocalHashEmbeddingService`），属于词面匹配而非语义向量；
> 要获得真正的语义检索需替换为真实 Embedding 模型（`EmbeddingService` 已预留接口）。

### 4.4 SSE 流式协议（GET /api/agent/stream）

事件流：`start → intent → planning → tool_call → tool_result → rag_result → plan_update → focus → confirmation → complete`。
Agent 通过 **GUI Focus 协议** 返回 focus（day/itemId/坐标），前端自动切换 Day、滚动时间轴、地图 flyTo。

### 4.5 工具清单（8 个，全部只读）

`search_attraction` / `search_hotel` / `search_restaurant` / `query_weather` / `calculate_route` /
`calculate_budget` / `get_itinerary` / `search_city_online`

> 写操作（新增/修改/删除行程项、撤销）不是工具，而是 `AgentPlan` 的操作项，统一经
> preview → Diff → 用户确认 → `ExecutorAgent.commit` 落地，并写入操作历史以便撤销。

### 4.6 撤销与操作历史

所有会改变行程的入口都会写一条 `operation_history`（before / after 快照），点「撤销」即把行程整体还原到上一步之前：

| 操作来源 | 记录类型 | 撤销效果 |
| --- | --- | --- |
| Agent 方案确认 | `AGENT_COMMIT` | 还原到确认前的行程 |
| 界面手工增/删/改行程项 | `MANUAL_EDIT` | 还原到手工操作前（不会连带冲掉别的手工改动） |
| Agent 新建行程 | `TRIP_CREATE` | **删除整个行程**（含行程项与账单），而不是留下一条空壳行程 |

删除行程时会级联清理它的行程项、账单与操作历史，避免出现"计入总支出却无法删除"的孤儿账单。

### 4.7 自然语言理解的当前边界（规则路径）

没有配置 `LLM_API_KEY` 时全部走规则引擎，以下是可预期的行为：

| 说法 | 结果 |
| --- | --- |
| 「明天下雨，把户外景点换掉」 | 按行程天数映射到某一天；**若"明天"不在行程区间内**（行程尚未开始或已结束），按「当前天 + 1」顺推并在回复里说明"已按 Day N 处理" |
| 「明天下午3点开评审会」 | 创建日程（需要**明确时间点**或"排个/预约/加入日程"这类排期动词） |
| 「今天开会好累」 | 不会创建日程，按闲聊处理并返回能力提示 |
| 「每天上午9点提醒我吃早饭」「每周一上午10点例会」 | 创建重复日程（每天 / 每周 / 每月），标题会去掉"提醒我""每周一"等词 |
| 「记一笔午餐 45 元」/「记一下明天办签证」 | 分别识别为记账与备忘（区分依据是能否解析出金额 + 记账动词） |
| 「国庆去杭州」「11月1日杭州三日游」「下周六去成都」 | 出发日期按你说的算（完整日期 / 月日 / 国庆·元旦·五一 / 周末 / 本周六 / 下周六 / 明天）；月日若今年已过自动顺延到明年，明确写出已过去的完整日期会拒绝并提示 |
| 其它无法归类的话 | 返回能力提示，不会贸然开始规划 |

### 4.8 预算口径与确认时的版本校验

预算统一用一张「执行摘要」表达（`GET /api/trips/{id}/budget`），Agent 的预算问答与行程页「预算分析」抽屉共用同一份数据：

| 字段 | 含义 |
| --- | --- |
| `planned` | 计划成本：行程项 `cost` 合计 |
| `budget` | 行程预算 |
| `actual` | 实际支出：记账合计 |
| `variance` | `actual - planned`（正数=比计划多花） |
| `remaining` | `budget - actual` |
| `categories` | 按记账分类的「计划 vs 实际」对照（酒店→住宿、餐饮→餐饮、交通→交通、景点→门票） |

待确认方案会记录生成时的**行程指纹**（日期 + 预算 + 全部条目）。确认时若指纹与当前行程不一致
（例如另一个标签页改过安排），方案会直接失效并提示重新生成，避免按过期 Diff 覆盖新数据。

### 4.9 日程与出发提醒

提醒由服务端**按当前时间实时计算**，前端每 60 秒轮询，不依赖推送基础设施：

| 类型 | 出现时机 | 说明 |
| --- | --- | --- |
| 日程 | 开始前 30 分钟 | 事件结束后 6 小时内仍可见并标记「已过期」；无具体时间的日程按当天 09:00 |
| 重复日程 | 每条按「下一次发生日期」计算 | 支持每天 / 每周 / 每月，可设结束日期；今天该时刻已过则顺延到下一次 |
| 行程出发 | 出发前一天 09:00 | 持续到出发日 23:59；已取消的行程不提醒 |

「知道了」会写入 `dismissedAt`，该提醒不再出现。侧栏铃铛（带未处理数量）与首页「待提醒」卡片共用同一份数据；
按事件发生时间排序，马上要做的排在前面。

在提醒抽屉里可以开启**浏览器通知**：授权后，页面在后台时也会弹出系统通知（同一提醒每个会话只弹一次）。

## 5. REST API 一览

```
POST   /api/auth/register         POST /api/auth/login

GET    /api/trips                 POST /api/trips
GET    /api/trips/{id}            PUT  /api/trips/{id}      DELETE /api/trips/{id}
PUT    /api/trips/{id}/status     # 确认 / 取消 / 恢复（阶段由日期派生）
GET    /api/trips/{id}/itinerary  POST /api/trips/{id}/itinerary
GET    /api/trips/map             # 首页足迹地图坐标
GET    /api/trips/{id}/budget     # 预算执行：计划 / 预算 / 实际 / 偏差 + 分类对账
PUT    /api/itinerary/{id}        DELETE /api/itinerary/{id}

POST   /api/agent/chat            # 同步对话
GET    /api/agent/stream          # SSE 流式对话（token 走查询参数）
POST   /api/agent/confirm         # 确认提交 planId
POST   /api/agent/cancel          # 取消方案
POST   /api/agent/undo            # 撤销上一次修改

GET    /api/trips/{tripId}/expenses   POST /api/trips/{tripId}/expenses
GET    /api/expenses/overview         DELETE /api/expenses/{id}

POST   /api/trips/{tripId}/photos     # 上传行程照片（multipart，≤10MB）
GET    /api/trips/{tripId}/photos
GET    /api/photos/{id}/raw           # 原图（<img> 用，token 走查询参数）
DELETE /api/photos/{id}

GET    /api/system/status             # 运行能力自检：模型/定位/时区（不返回密钥）

GET    /api/reminders                 # 提醒板：日程 + 出发提醒
POST   /api/reminders/schedule/{id}/dismiss
POST   /api/reminders/trip/{id}/dismiss

POST   /api/office/invoice/{id}/expense   # 发票一键记一笔（写入所选行程，防重复）

GET    /api/memos                 POST /api/memos
POST   /api/memos/{id}/toggle     DELETE /api/memos/{id}
GET    /api/schedule              POST /api/schedule
POST   /api/schedule/{id}/toggle  DELETE /api/schedule/{id}

POST   /api/office/mail/classify  GET  /api/office/mail
POST   /api/office/minutes/generate   GET /api/office/minutes
POST   /api/office/invoice/extract    GET /api/office/invoice
POST   /api/office/invoice/upload     # 上传 PDF/图片发票（图片需配置 OCR）
POST   /api/office/report/generate    GET /api/office/report

POST   /api/learning/generate     GET  /api/learning/artifacts
DELETE /api/learning/artifacts/{id}
POST   /api/learning/knowledge/add    POST /api/learning/knowledge/search

POST   /api/health/record         POST /api/health/seed-demo
GET    /api/health/records        GET  /api/health/summary
```

统一响应：`{"code":0,"message":"success","data":...}`。
**code 与 HTTP 状态码保持一致**：参数校验失败 400、未登录 401、无权限 403、资源不存在 404、数据冲突 409、服务端异常 500（不返回内部细节）。

## 6. 配置说明

### 6.1 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | 空 | Base64 密钥（解码后 ≥ 32 字节）。为空时启动生成一次性随机密钥，重启后 token 失效；**生产必须显式配置** |
| `JWT_EXPIRE_HOURS` | 72 | token 有效期（小时） |
| `LLM_API_KEY` | 空 | 为空则全部走规则引擎（功能完整，效果弱于模型） |
| `LLM_BASE_URL` | `https://api.deepseek.com/v1` | 任意 OpenAI-compatible 端点 |
| `LLM_MODEL` | `deepseek-chat` | 模型名，可换 `qwen-plus`、`gpt-4o-mini` 等 |
| `LLM_ENABLED` | true | 设 false 可强制关闭模型调用 |
| `AMAP_KEY` | 空 | 高德 Web 服务 Key，配置后城市 POI 定位走真实数据 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | 允许的前端来源 |
| `APP_TIMEZONE` | `Asia/Shanghai` | 应用时区。代码里的「今天/明天」与日报、记账日期都基于它；容器默认 UTC 时必须显式确认 |
| `TESSERACT_CMD` | 空 | 图片发票 OCR 的 Tesseract 可执行文件路径；不配置时 PDF 电子发票与粘贴文本仍可正常识别 |
| `INVOICE_DIR` | `data/invoices` | 上传的发票原始文件目录（已 gitignore） |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | localhost / 3306 / travel_agent / root / root | 仅在 `mysql` profile 下生效 |

### 6.2 接入真实大模型（可选）

```bash
cd travel-agent-server
export LLM_API_KEY="sk-xxx"
export LLM_BASE_URL="https://api.deepseek.com/v1"   # 或 dashscope.aliyuncs.com/compatible-mode/v1
export LLM_MODEL="deepseek-chat"                    # 或 qwen-plus / gpt-4o-mini
mvn spring-boot:run
```

模型仅用于意图识别增强、方案生成与文案润色；调用失败（余额不足、网络异常）会自动降级为规则引擎，主流程不中断。

### 6.3 切换 MySQL

```bash
docker compose up -d                 # 启动 MySQL（自动执行 sql/init.sql）与 Redis
cd travel-agent-server
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

## 7. 测试与 CI

```bash
# 后端：集成测试（内存 H2，不访问网络、不调用真实模型）
cd travel-agent-server && mvn test

# 前端：类型检查 + 单元测试 + 生产构建
cd travel-agent-web && npm run typecheck && npm test && npm run build
```

当前覆盖（后端 49 个 / 前端 28 个）：

- 认证与授权：401 契约、跨用户读写与删除行程；
- Agent 计划落地：ADD/MODIFY/REMOVE、跨行程操作守卫、确认提交与撤销（含撤销新建行程）；
- 工具层契约：只读工具才允许注册、风险评估等级、业务错误原样透出；
- 理解精度：日期→行程天映射、意图路由（闲聊不被误写成日程）、时间冲突检查（含 MODIFY）；
- 数据一致性：删除行程的级联清理、记账分项与总支出对账；
- 运行环境：应用时区固定；前端流式状态机与 SSE 中断路径。

CI（[.github/workflows/ci.yml](.github/workflows/ci.yml)）在 push / PR 时执行 `mvn -B verify` 与
`npm ci && npm run typecheck && npm test && npm run build`。

## 8. 后续规划

已完成的工程化改造：密钥外置、版本控制、测试与 CI、原子化提交、参数校验、错误语义对齐、前端路由懒加载与分包、
3D 足迹地图，以及一轮逻辑缺陷修复（详见 [docs/CHANGELOG.md](docs/CHANGELOG.md)）。

继续推进的方向：
- 真实数据源：高德/百度 POI 与路径规划、和风天气、酒店/餐厅开放平台；
- 向量检索升级：真实 Embedding + Pgvector/Milvus 替换内存向量库（`VectorStore` 接口已预留）；
- 多实例部署：Redis 存储待确认会话（`PendingPlanStore`）与限流、每用户 LLM 配额；
- 安全加固：SSE 改用一次性短时 ticket 替代 query 传 token、token 存储从 localStorage 迁出、接口限流；
- 数据库：Flyway/Liquibase 管理 schema（当前 `ddl-auto: update`）、补 `trip_id`/`user_id` 索引与列表分页；
- 可观测性：Actuator 健康检查、指标与 traceId、LLM 调用失败率监控；
- 前端：依赖漏洞升级（`npm audit` 中的 echarts / maplibre-gl / vite / react-router）、ECharts 按需引入、地图引擎收敛为一套；
- 会话与预算：多轮上下文（当前 `sessionId` 每次请求重建，只能靠 selectedElement 指代）、"计划成本 vs 实际支出"对比视图；
- 知识库运营：`RagBootstrap` 目前"有文档就跳过"，更新 `knowledge.json` 需手工清库；在线城市缓存的模糊匹配可能串城市；
- 前端体验：待确认方案刷新后丢失（后端已有 30 分钟 TTL，但缺按 planId 取回的接口）；
- 二期工具：compare_hotel / check_opening_hours / search_ticket / generate_trip_summary。
