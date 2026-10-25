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

- **会话记忆**：前端生成 `conversationId`（sessionStorage，跨页面切换保持同一对话），
  后端按「用户 + 会话」存最近 10 轮消息，喂给 LLM 做指代消解——「那把第二天的酒店也换掉」
  这类追问能接住；聊天面板「新对话」按钮开新会话并清掉服务端历史（保留 7 天后定期清理）。
  未配置 LLM 时规则路径行为不变；
- **IntentAgent**：LLM 优先（带会话历史）+ 规则兜底，输出 `{intent, target, constraints}`（规则边界见 4.7）；
- **PlannerAgent**：结合行程/预算/偏好/天气/交通生成 `AgentPlan`（操作列表，不直接落库）；
- **ExecutorAgent**：preview（生成 Diff + 确认请求）→ 用户确认 → commit（单事务内 Service → Repository + 操作历史）；
- **ReflectAgent**：基于「应用计划之后」的行程做时间冲突检查（覆盖 ADD 与 MODIFY 造成的重叠）与预算超限校验，输出警告；
- **文案润色**：方案确认消息先由模板生成（保证事实准确），配置 LLM 后再由模型重写得更自然——
  模型输出做长度与关键金额校验，不达标回退模板原文；润色走 8s 独立读超时，不拖慢对话链路。

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
- **Tools**：天气 / 路线 / 酒店价格等实时信息。天气默认确定性模拟（`WEATHER_REAL=true` 时接
  Open-Meteo 真实预报，失败自动降级模拟）；酒店/餐厅价格仍为模拟数据，接口与真实服务一致。

> 当前 Embedding 为本地字符 bigram 哈希（`LocalHashEmbeddingService`），属于词面匹配而非语义向量；
> 要获得真正的语义检索需替换为真实 Embedding 模型（`EmbeddingService` 已预留接口）。

### 4.4 SSE 流式协议（GET /api/agent/stream）

事件流：`start → intent → planning → tool_call → tool_result → rag_result → plan_update → focus → confirmation → complete`。
Agent 通过 **GUI Focus 协议** 返回 focus（day/itemId/坐标），前端自动切换 Day、滚动时间轴、地图 flyTo。

### 4.5 工具清单（8 个内置 + 可选 MCP 外部工具，全部只读）

内置：`search_attraction` / `search_hotel` / `search_restaurant` / `query_weather` / `calculate_route` /
`calculate_budget` / `get_itinerary` / `search_city_online`

接入 MCP 后（见 §4.10），外部工具以 `mcp_{server}_{tool}` 注册进同一注册表，与内置工具同受
SafetyInterceptor 审计。

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

### 4.10 路书导出 / 多渠道入口 / MCP 工具生态

**路书导出**：行程页「路书」按钮 → `GET /api/trips/{id}/guidebook.pdf`，服务端把
行程概况 + 每日安排表 + 预算小结渲染成打印友好的 HTML 再转 PDF（flying-saucer + OpenPDF，
登录态走 httpOnly Cookie，同源 GET 直接可用）。中文按「classpath:fonts/cjk.* → Windows →
Linux/macOS」顺序探测字体；一个都找不到时报业务错误并提示安装方式，不会导出乱码方块。

**多渠道入口**：网页之外的渠道复用同一个 `AgentEngine`（同一套账号、数据与确认/撤销机制）：

| 渠道 | 启用方式 | 说明 |
| --- | --- | --- |
| Telegram Bot | 配置 `TELEGRAM_BOT_TOKEN` | 长轮询（纯出站连接，本地可跑）；每个 chat 自动开通本地账号 `tg{chatId}`，会话记忆按 chat 维持；产生待确认方案时只播报并提示去网页端确认 |
| 通用 Webhook | `CHANNEL_WEBHOOK_ENABLED=true` + `CHANNEL_WEBHOOK_SECRET` | `POST /api/channels/webhook`（X-Webhook-Secret 头恒时比较），给企业微信/钉钉/自建脚本桥接；body 指定本地 username，不自动注册 |

**MCP 工具生态**：`travel.mcp.servers`（`MCP_ENABLED=true`）启动时把外部 MCP server 的工具
注册进 ToolRegistry（`mcp_{server}_{tool}`，只读，单个 server 失败只告警）。传输支持
streamable HTTP（`url`）与本机子进程 stdio（`command`，如 `npx -y @amap/amap-maps-mcp-server`）。
启用后 LLM 意图识别在用户明确要查外部数据时输出 `INVOKE_TOOL`（工具名 + 参数），由
`ToolInvoker` 执行并回播结果；未接入 MCP 或 LLM 不可用时，意图层不产出该意图，行为与从前一致。

## 5. REST API 一览

```
POST   /api/auth/register         POST /api/auth/login
GET    /api/auth/me               # 凭 httpOnly Cookie 恢复登录态（刷新后用）
POST   /api/auth/logout           # 清登录 Cookie
POST   /api/auth/access-ticket    # 签发短期只读票据（SSE / 照片原图用）

GET    /api/trips                 POST /api/trips
GET    /api/trips/{id}            PUT  /api/trips/{id}      DELETE /api/trips/{id}
PUT    /api/trips/{id}/status     # 确认 / 取消 / 恢复（阶段由日期派生）
GET    /api/trips/{id}/itinerary  POST /api/trips/{id}/itinerary
GET    /api/trips/map             # 首页足迹地图坐标
GET    /api/trips/{id}/budget     # 预算执行：计划 / 预算 / 实际 / 偏差 + 分类对账
GET    /api/trips/{id}/guidebook.pdf  # 路书导出（PDF，登录态凭 Cookie）
PUT    /api/itinerary/{id}        DELETE /api/itinerary/{id}

POST   /api/agent/chat            # 同步对话
GET    /api/agent/stream          # SSE 流式对话（短期只读票据走查询参数，含 conversationId 会话记忆）
POST   /api/agent/confirm         # 确认提交 planId
POST   /api/agent/cancel          # 取消方案
POST   /api/agent/undo            # 撤销上一次修改
GET    /api/agent/plans/{planId}  # 取回待确认方案（刷新页面后恢复确认弹窗）
POST   /api/agent/conversation/clear  # 开新会话：清掉当前用户在该会话下的历史消息
POST   /api/auth/access-ticket    # 签发短期只读票据（SSE / 照片原图用，替代在 URL 传登录 JWT）

GET    /api/trips/{tripId}/expenses   POST /api/trips/{tripId}/expenses
GET    /api/expenses/overview         DELETE /api/expenses/{id}

POST   /api/trips/{tripId}/photos     # 上传行程照片（multipart，≤10MB）
GET    /api/trips/{tripId}/photos
GET    /api/photos/{id}/raw           # 原图（<img> 用，短期票据走查询参数）
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

POST   /api/channels/webhook      # 多渠道通用入口（X-Webhook-Secret 即凭证，未启用/未配置一律 401）
```

统一响应：`{"code":0,"message":"success","data":...}`。
**code 与 HTTP 状态码保持一致**：参数校验失败 400、未登录 401、无权限 403、资源不存在 404、数据冲突 409、服务端异常 500（不返回内部细节）。

## 6. 配置说明

### 6.1 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | 空 | Base64 密钥（解码后 ≥ 32 字节）。为空时启动生成一次性随机密钥，重启后 token 失效；**生产必须显式配置** |
| `JWT_EXPIRE_HOURS` | 72 | token 有效期（小时），httpOnly Cookie 的 maxAge 与之对齐 |
| `COOKIE_SECURE` | false | https 部署时设 true，登录 Cookie 加 Secure 属性 |
| `WEATHER_REAL` | false | true 时天气走 Open-Meteo 真实预报（免费无 Key），失败自动降级模拟 |
| `LLM_API_KEY` | 空 | 为空则全部走规则引擎（功能完整，效果弱于模型） |
| `LLM_BASE_URL` | `https://api.deepseek.com/v1` | 任意 OpenAI-compatible 端点 |
| `LLM_MODEL` | `deepseek-chat` | 模型名，可换 `qwen-plus`、`gpt-4o-mini` 等 |
| `LLM_ENABLED` | true | 设 false 可强制关闭模型调用 |
| `AMAP_KEY` | 空 | 高德 Web 服务 Key，配置后城市 POI 定位走真实数据 |
| `TELEGRAM_BOT_TOKEN` | 空 | Telegram Bot token（@BotFather 创建）。配置即启用该渠道（长轮询），留空渠道关闭 |
| `CHANNEL_WEBHOOK_ENABLED` | false | true 时启用通用 Webhook 渠道入口 |
| `CHANNEL_WEBHOOK_SECRET` | 空 | Webhook 渠道密钥（X-Webhook-Secret 头，恒时比较）；未配置时端点始终 401 |
| `MCP_ENABLED` | false | true 时启动接入 `travel.mcp.servers` 里的 MCP server（见 §4.10 与 application.yml 示例） |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | 允许的前端来源 |
| `APP_TIMEZONE` | `Asia/Shanghai` | 应用时区。代码里的「今天/明天」与日报、记账日期都基于它；容器默认 UTC 时必须显式确认 |
| `TESSERACT_CMD` | 空 | 图片发票 OCR 的 Tesseract 可执行文件路径；不配置时 PDF 电子发票与粘贴文本仍可正常识别 |
| `TESSDATA_PREFIX` | 空 | Tesseract 语言包目录，需包含 `chi_sim.traineddata` 与 `eng.traineddata`；仅在语言包装在非默认目录时需要 |
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

### 6.3 图片发票 OCR（Windows）

图片发票需要本机安装 Tesseract，并把可执行文件与语言包目录告诉后端：

```powershell
# 1. 安装 Tesseract（已安装可跳过）
winget install --id UB-Mannheim.TesseractOCR -e

# 2. 准备中英语言包（Program Files 无写权限时放到用户目录）
mkdir C:\Users\<你>\tools\tessdata
copy "C:\Program Files\Tesseract-OCR\tessdata\eng.traineddata" C:\Users\<你>\tools\tessdata\
copy "C:\Program Files\Tesseract-OCR\tessdata\osd.traineddata" C:\Users\<你>\tools\tessdata\
Invoke-WebRequest https://github.com/tesseract-ocr/tessdata_fast/raw/main/chi_sim.traineddata `
  -OutFile C:\Users\<你>\tools\tessdata\chi_sim.traineddata

# 3. 写入 travel-agent-server\.env.local（已 gitignore）
# TESSERACT_CMD=C:/Program Files/Tesseract-OCR/tesseract.exe
# TESSDATA_PREFIX=C:/Users/<你>/tools/tessdata
# 注意：.env.local 按 properties 解析，Windows 路径请用正斜杠 /，反斜杠会被当作转义符

# 4. 校验语言包
& "C:\Program Files\Tesseract-OCR\tesseract.exe" `
  --tessdata-dir C:\Users\<你>\tools\tessdata --list-langs
```

重启后端后，`GET /api/system/status` 的 `ocrConfigured` 应为 `true`。上传接口仍为
`POST /api/office/invoice/upload`，图片按 `chi_sim+eng`、`--psm 6` 识别。

### 6.4 切换 MySQL

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

当前覆盖（后端 155 个 / 前端 46 个）：

- 认证与授权：401 契约、跨用户读写与删除行程；
- Agent 计划落地：ADD/MODIFY/REMOVE、跨行程操作守卫、确认提交与撤销（含撤销新建行程）；
- 工具层契约：只读工具才允许注册、风险评估等级、业务错误原样透出；
- 理解精度：日期→行程天映射、意图路由（闲聊不被误写成日程）、时间冲突检查（含 MODIFY）；
- 数据一致性：删除行程的级联清理、记账分项与总支出对账；
- 扩展能力：路书 PDF（内容 + 越权）、MCP 桥接（HTTP/stdio 传输、注册与调用、坏服务器跳过）、
  多渠道入口（Webhook 密钥契约、Telegram 消息→引擎→回复与账号自动开通）；
- 运行环境：应用时区固定；前端流式状态机与 SSE 中断路径。

CI（[.github/workflows/ci.yml](.github/workflows/ci.yml)）在 push / PR 时执行 `mvn -B verify` 与
`npm ci && npm run typecheck && npm test && npm run build`。

## 8. 后续规划

已完成的工程化改造：密钥外置、版本控制、测试与 CI、原子化提交、参数校验、错误语义对齐、前端路由懒加载与分包、
3D 足迹地图、多轮会话记忆、方案文案 LLM 润色、真实天气（Open-Meteo）、httpOnly Cookie 登录态、Flyway 增量迁移、
SSE/照片短期票据、接口限流、待确认方案落库（多实例可用）、数据库索引与分页、路书 PDF 导出、
多渠道入口（Telegram / 通用 Webhook）、MCP 外部工具生态，以及若干轮逻辑缺陷修复与安全加固
（详见 [docs/CHANGELOG.md](docs/CHANGELOG.md)）。

按优先级继续推进：

**P0 产品力**
- LLM 结构化规划：让模型输出 JSON 操作列表、规则引擎校验兜底，替代当前「模板拼装 + 文案润色」；
- 真实数据源：优先经 §4.10 的 MCP 通道接高德 POI / 路径规划（stdio 接 @amap/amap-maps-mcp-server），
  酒店/餐厅开放平台同理（天气已接 Open-Meteo）。

**P1 健壮性**
- Spring Boot 3.3 → 3.5（3.3 OSS 支持已终止）；
- 前端：核心交互组件测试（ChatPanel / PlanDiff 确认流程），e2e 脚本挂进 CI；
- 多实例收尾：限流计数迁移 Redis、照片迁移对象存储。

**P2 部署与运营**
- 全栈 Docker 化（应用镜像 + compose 一键起；路书导出镜像内需安装 fonts-noto-cjk）；
- 每用户 LLM 配额（按天限制模型开销，限流目前只按请求数）；
- 向量检索升级：真实 Embedding + Pgvector/Milvus 替换内存向量库（`VectorStore` 接口已预留）；
- 会话与预算：「计划成本 vs 实际支出」偏差视图；PWA 离线查看行程；
- 知识库运营：`RagBootstrap` 目前"有文档就跳过"，更新 `knowledge.json` 需手工清库（缺版本/指纹机制）；
- 渠道深化：微信公众号新消息回调适配器（XML 协议，需公网回调地址）；Agent 可发起路书导出（generate_trip_summary 工具化）；
- 二期工具：compare_hotel / check_opening_hours / search_ticket。
