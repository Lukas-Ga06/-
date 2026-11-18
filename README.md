# AI 个人助理（旅行规划 · 记账 · 备忘 · 办公 · 学习 · 健康）

以「行前规划 + 行中动态调整」为主线的个人助理系统，同时覆盖记账、备忘、日程、办公自动化（邮件/纪要/日报/发票）、学习辅助与健康数据。

**技术路线**：Spring Boot 3 + Agent Engine（Intent / Planner / Executor / Reflect）+ RAG + Tools + React 18
**核心交互**：Voice/Text + Map + Timeline + Card
**核心闭环**：自然语言表达意图 → Agent 理解与规划 → Tools 执行 → RAG 提供知识 → GUI 可视化 → 用户确认 → 行程更新

> 仓库目录沿用 `travel-agent`，前端标题为「AI 个人助理」：旅行是核心场景，其余模块复用同一套 Agent 引擎与权限体系。

---

## 文档导航

完整文档套件在 **[docs/guide/](docs/guide/README.md)**，按角色选入口：

| 文档 | 内容 |
| --- | --- |
| [docs/guide/README.md](docs/guide/README.md) | 文档索引：角色阅读路径、模块速览、一分钟心智模型 |
| [docs/guide/code-structure.md](docs/guide/code-structure.md) | 代码结构手册：分层依赖、包职责、关键协作类、新增代码落点、命名约定、维护红线 |
| [docs/guide/getting-started.md](docs/guide/getting-started.md) | 快速上手：环境要求、启动、演示闭环 walkthrough、常见问题排查 |
| [docs/guide/architecture.md](docs/guide/architecture.md) | 架构与核心设计：状态机、四 Agent、工具层、RAG、SSE 协议、MCP、配额 |
| [docs/guide/api-reference.md](docs/guide/api-reference.md) | REST API 参考：通用约定（认证/票据/错误码）+ 分模块逐接口契约 |
| [docs/guide/data-model.md](docs/guide/data-model.md) | 数据模型：ER 图、全部表结构、Flyway 策略、一致性不变量 |
| [docs/guide/frontend.md](docs/guide/frontend.md) | 前端结构：路由表、页面清单、状态管理、SSE 消费链路、组件说明 |
| [docs/guide/deployment.md](docs/guide/deployment.md) | 部署与运维：Docker 全栈、生产必改项、环境变量权威表、监控排障 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 开发约定：关键不变量、测试规范、设计系统 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 行为变化记录 |

---

## 1. 项目结构

```
travel-agent/
├── sql/init.sql                        # MySQL 初始化脚本（H2 模式无需执行）
├── docker-compose.yml                  # 全栈一键部署：MySQL + 后端 + 前端(nginx)
├── docker-compose.infra.yml            # 本地开发可选：MySQL + Redis 基础设施
├── .github/workflows/ci.yml            # CI：后端 mvn verify + 前端 typecheck/test/build
├── docs/guide/                         # 完整项目文档套件（见下方「文档导航」）
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
└── travel-agent-web/                   # 前端（React 18 + TypeScript + Vite + AntD + ECharts + 高德/MapLibre）
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
本地开发要切 MySQL/Redis 时运行 `docker compose -f docker-compose.infra.yml up -d` 并按第 6 节修改配置。

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

> 需要 H2 控制台（`http://localhost:8080/h2-console`）时设 `H2_CONSOLE_ENABLED=true` 再启动；
> 默认关闭——控制台用 `sa` 空密码直连数据库，暴露到公网等于匿名读写全库。
> 生产部署（`mysql`/`prod` profile）下若仍开启 H2 控制台、demo 账号或 `COOKIE_SECURE=false`，
> 启动日志会打印 `[SECURITY]` 告警，请按提示关闭。

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

行程地图已接入高德 JS API；足迹地图默认也是高德栅格底图（MapLibre 球体保留），
`VITE_FOOTPRINT_BASEMAP=osm` 可回退 OSM。首次开发前，将 `.env.example` 复制为 `.env.local`，填写高德开放平台的
**Web 端(JS API)** Key（`VITE_AMAP_KEY`）及配套安全密钥（`VITE_AMAP_SECURITY_CODE`）。
它与后端用于 POI 定位的 **Web 服务** Key（`AMAP_KEY`）是两种不同凭据，不能混用。

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

> 上图是概念流程。`AgentState` 枚举实际声明 10 个值，其中 `REPLAN` 在第十三批已真正启用：
> 审核发现 HIGH 级硬约束问题（预算装不下 / 转场来不及 / 真实预报有雨仍排户外）时进入该状态做有限重规划。
> `CONFIRM` / `COMMIT` 仍不在 SSE 主链路中设置——确认与提交是前端经 `POST /api/agent/confirm` 触发的独立调用。
> 精确的状态转移与「哪些状态真正被 set」见 [docs/guide/architecture.md](docs/guide/architecture.md) §2。

- **会话记忆**：前端生成 `conversationId`（sessionStorage，跨页面切换保持同一对话），
  后端按「用户 + 会话」存最近 **10 条消息（约 5 轮）**，喂给 LLM 做指代消解——「那把第二天的酒店也换掉」
  这类追问能接住；聊天面板「新对话」按钮开新会话并清掉服务端历史（保留 7 天后由每日定时任务清理）。
  历史只参与意图识别，不进入规划产物；未配置 LLM 时规则路径行为不变；条数与保留天数可配
  （`CONVERSATION_HISTORY_LIMIT` / `CONVERSATION_RETENTION_DAYS`）；
- **IntentAgent**：LLM 优先（带会话历史）+ 规则兜底，输出 `{intent, target, constraints}`（规则边界见 4.7）；
- **PlannerAgent**：结合行程/预算/偏好/天气/交通生成 `AgentPlan`（操作列表，不直接落库）；
- **ExecutorAgent**：preview（生成 Diff + 确认请求）→ 用户确认 → commit（单事务内 Service → Repository + 操作历史）；
- **ReflectAgent**：基于「应用计划之后」的行程做审核——预算超限、转场时间不够、真实预报下雨仍排户外为
  **HIGH 硬约束**，时间冲突为 MEDIUM 提醒；HIGH 问题先交给 `PlanRepairService` 做确定性重规划（雨天换室内 /
  精简景点 / 后移时间），两轮仍修不好则确认门上必须显式接受风险；
- **PlanInspector**：审核与修复共用的「应用计划后的事实视图」（逐日条目 / 相邻转场路线与时长 / 逐日天气）；
- **文案润色**：方案确认消息先由模板生成（保证事实准确），配置 LLM 后再由模型重写得更自然——
  模型输出做长度与关键金额校验，不达标回退模板原文；润色走 8s 独立读超时，不拖慢对话链路。

### 4.2 Human-in-the-loop 三级风险

风险等级**只用于方案级判定**（`SafetyInterceptor.evaluatePlanChange`），由 Agent 写成 `confirmation.riskLevel`，前端据此决定确认框的样式：

| 等级 | 判定条件 | 策略 |
| --- | --- | --- |
| LOW | 只读操作（查询、推荐） | 自动执行 |
| MEDIUM | 会改变行程 | 生成 Diff，确认后提交 |
| HIGH | 变更后超出行程预算；或审核仍有未解决 HIGH 风险（预算装不下 / 转场不可能 / 真实预报雨天排户外） | Diff + 醒目警告；后者必须勾选「我已了解风险」（`acknowledgeIssues=true`）才能提交 |

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

事件流：`start → run → intent → planning → tool_call → tool_result → rag_result → plan_update → focus → replan → confirmation → complete`
（`replan` 只在审核发现 HIGH 问题、进入有限重规划时出现，最多 2 次）。
Agent 通过 **GUI Focus 协议** 返回 focus（day/itemId/坐标），前端自动切换 Day、滚动时间轴、地图 flyTo。

### 4.5 工具清单（8 个内置 + 可选 MCP 外部工具，全部只读）

内置：`search_attraction` / `search_hotel` / `search_restaurant` / `query_weather` / `calculate_route` /
`calculate_budget` / `get_itinerary` / `search_city_online`

接入 MCP 后（见 §4.10），外部工具以 `mcp_{server}_{tool}` 注册进同一注册表，与内置工具同受
SafetyInterceptor 审计。

> 写操作（新增/修改/删除行程项、撤销）不是工具，而是 `AgentPlan` 的操作项，统一经
> preview → Diff → 用户确认 → `ExecutorAgent.commit` 落地，并写入操作历史以便撤销。

### 4.5.1 Agent 自主循环、确认门与同步（V2.2）

V2.2 在不改变既有确认/撤销/预算口径的前提下补了以下几件事（详见 [docs/guide/v2.2-iteration.md](docs/guide/v2.2-iteration.md)）：

| 能力 | 机制 | 关键保障 |
| --- | --- | --- |
| 运行态追踪 | 一次对话 = `agent_run`，阶段 = `agent_task`，工具调用 = `tool_call_trace` | 独立事务写入，追踪故障不影响主流程；保留 14 天 |
| 自主工具调用 | `AgentToolLoop` 让 research-agent 在只读白名单内自选工具、看结果、再决定 | 步骤预算 / 同参去重 / 白名单 / 协作式取消；无 LLM 完全降级 |
| 确认绑定内容 | `confirmation.contentHash`（方案 SHA-256）+ 既有 `planRevision` | 双指纹：一个拦「行程被改」，一个拦「方案被换」 |
| 确认后同步 | `PlanExecutionService` 逐模块写日程 / 预算快照 / 足迹 | `plan_id` 唯一 + `source_key` 幂等；部分失败可单独重试 |
| 真实路线 | `RouteService`：高德 MCP → REST → 本地估算 | 结果带 `source` 标记，估算不伪装成实时；10 分钟缓存 |
| 超预算自愈 | 酒店降级后仍超 → 逐次移除最贵景点（每天保底 1 个） | 确定性收敛，调整原因写进方案说明 |
| **审核改变结果（FR-06）** | 审核不通过 → `REPLAN` 有限重规划（≤2 次）→ 再审 | 修复确定性可解释；仍不通过则确认门要求显式接受风险，方案不被静默放行 |
| **路线段进 UI（FR-07）** | 时间线插入转场行 + 地图按段绘制 | `estimated` 虚线 / `amap` 实线；无数据退回原点位连线 |
| **需求槽位（FR-01）** | 缺目的地/天数/人数 → `RequirementSlots` 合并追问 → 补槽后规划 | 一次问齐；跨轮续槽；拒绝回答才兜底且兜底值明示；人数贯通预算 |

**两条不可违反的口径**：预算同步只记「计划快照」，**绝不**自动生成记账（估算 ≠ 已消费）；
足迹只标「计划去」，**绝不**把未来行程写成「已到访」。

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
| 微信公众号 | 配置 `WECHAT_OA_TOKEN`（明文模式）；可选 `WECHAT_OA_APPID/SECRET` 开客服消息 | `GET/POST /api/channels/wechat`：接入签名验证 + XML 被动回复；openid 自动开通 `wx_*` 账号。配 appid/secret 后改异步客服消息回复（解除被动 5s 窗口） |
| 通用 Webhook | `CHANNEL_WEBHOOK_ENABLED=true` + `CHANNEL_WEBHOOK_SECRET` | `POST /api/channels/webhook`（X-Webhook-Secret 头恒时比较），给企业微信/钉钉/自建脚本桥接；body 指定本地 username，不自动注册 |

**LLM 结构化规划**：配置 LLM 后，`PlannerAgent` 优先走结构化路径——模型从「候选清单」
（目录景点/餐厅/酒店，目录外城市为联网生成结果）中选择实体并编排时间，后端逐条校验
（名称必须命中清单、天数边界、时间格式、条数上限；坐标与票价一律以目录数据覆盖，模型不能编造），
失败自动完整回落既有规则路径。行程修改同理：REMOVE 按标题唯一命中现有条目，ADD 只收候选景点。
确认与提交链路不变（preview → Diff → confirm → commit）。

**LLM 每日配额**：`LLM_DAILY_QUOTA`（默认 0 不限）按用户限制每日全部 LLM 调用
（意图识别/规划/润色/办公），超限自动降级规则引擎；计数落库（`llm_quota_usage`），多实例共享。

**真实 Embedding**：`EMBEDDING_ENABLED=true` 时 RAG 用 OpenAI 兼容 `/embeddings` 端点
（SiliconFlow/智谱/OpenAI 等）替代本地字符 bigram 哈希；调用失败进程内锁定降级（保持向量空间一致）。
**切换 Embedding 模型后需清空 rag_document / rag_chunk 重建知识库**。

**多实例**：`RATE_LIMIT_STORE=redis` 限流计数跨实例共享（Lua 原子，Redis 故障 fail-open）；
`PHOTOS_STORE=s3` 照片存 S3 兼容对象存储（MinIO/OSS/COS，`travel.photos.s3.*` 配置）。

**MCP 工具生态**：`travel.mcp.servers`（`MCP_ENABLED=true`）启动时把外部 MCP server 的工具
注册进 ToolRegistry（`mcp_{server}_{tool}`，只读，单个 server 失败只告警）。传输支持
streamable HTTP（`url`）与本机子进程 stdio（`command`，如 `npx -y @amap/amap-maps-mcp-server`）。
启用后 LLM 意图识别在用户明确要查外部数据时输出 `INVOKE_TOOL`（工具名 + 参数），由
`ToolInvoker` 执行并回播结果；未接入 MCP 或 LLM 不可用时，意图层不产出该意图，行为与从前一致。

### 4.11 V2.2：自主 Agent 与确认后执行闭环

V2.1 的链路是「协调器预先检索事实 → 多个 LLM 专家并行分析 → Reviewer 汇总复核」。
V2.2 在不推翻既有**确认 / 撤销 / 预算口径**的前提下，补上三件此前不存在的能力
（完整设计决策、逐 FR 落地与已知差距见 **[docs/guide/v2.2-iteration.md](docs/guide/v2.2-iteration.md)**）：

| 能力 | 一句话 | 主要落点 |
| --- | --- | --- |
| **运行追踪（FR-02/04/10）** | 一次对话 = 一条 `agent_run`，各阶段 = `agent_task`，每次工具调用 = `tool_call_trace`；凭 runId 可回查整条链路 | `AgentTraceService` / `AgentTaskRecorder` / `GET /api/agent/runs*` |
| **自主工具调用（FR-03）** | `research-agent` 在只读白名单内自己决定「调哪个工具 / 什么参数 / 何时结束」，观察进入下一轮决策 | `AgentToolLoop` + `PlannerAgent.researchFacts` |
| **确认后幂等同步（FR-09）** | 确认提交后按模块同步日程 / 预算快照 / 足迹，逐模块记账、失败可单独重试 | `PlanExecutionService` + `plan_execution` + `schedule_item.source_key` |
| **内容指纹确认门（FR-08）** | 确认请求携带方案内容 SHA-256，绑定「用户看到的就是提交的内容」 | `PendingPlanStore` / `PlanConfirmationService` |
| **前端过程可视化** | Agent 活动时间线、执行结果面板（部分失败 + 重试）、确认弹窗展示问题与指纹 | `AgentActivityPanel` / `ExecutionResultPanel` / `PlanDiff` |

三条**不可动摇的红线**（都有测试守护）：

- **自主循环必须能「什么都没做」**：无 LLM / 配额耗尽 / 输出非法时 `completed=false`，规划完整回落到既有固定路径，Prompt 与旧版逐字一致。
- **预算同步只记快照**：估算绝不会自动变成「已消费」，实际支出必须手动记账/发票导入。
- **足迹只标计划态**：未来行程不会被记成「已到访」。

追踪写入全部走**独立事务**（`REQUIRES_NEW`，try/catch 在事务边界之外），追踪表故障不会反噬业务；
主流程失败/断连时已产生的痕迹仍然保留（保留 14 天，每日 04:40 清理）。

## 5. REST API 一览

```
POST   /api/auth/register         POST /api/auth/login
GET    /api/auth/me               # 凭 httpOnly Cookie 恢复登录态（刷新后用）
POST   /api/auth/logout           # 清登录 Cookie
POST   /api/access-ticket         # 签发短期只读票据（SSE / 照片原图用）

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
GET    /api/agent/runs            # 最近运行列表（V2.2 追踪：状态/意图/耗时）
GET    /api/agent/runs/{runId}    # 一次运行的完整追踪：任务图（依赖边）+ 工具调用（他人 404）
GET    /api/agent/executions/{executionId}        # 确认后日程/预算/足迹同步台账
POST   /api/agent/executions/{executionId}/retry  # 只重试失败模块（成功项不重复创建）
POST   /api/agent/conversation/clear  # 开新会话：清掉当前用户在该会话下的历史消息

GET    /api/agent/runs               # 最近的 Agent 运行（V2.2 追踪）
GET    /api/agent/runs/{runId}       # 运行明细：任务图（依赖边/尝试/替换）+ 工具调用
GET    /api/agent/executions/{id}    # 确认后同步台账（日程/预算/足迹逐模块状态）
POST   /api/agent/executions/{id}/retry  # 只重试失败的同步模块（幂等）

GET    /api/trips/{id}/route-legs    # 每日路线段：相邻点距离/时长/来源（amap|estimated）

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
| `DEMO_ACCOUNT` | true | 是否自动创建演示账号 demo/demo123（README 快速启动用）；**生产部署必须设 false** |
| `LLM_API_KEY` | 空 | 为空则全部走规则引擎（功能完整，效果弱于模型） |
| `LLM_BASE_URL` | `https://api.deepseek.com/v1` | 任意 OpenAI-compatible 端点 |
| `LLM_MODEL` | `deepseek-chat` | 模型名，可换 `qwen-plus`、`gpt-4o-mini` 等 |
| `LLM_ENABLED` | true | 设 false 可强制关闭模型调用 |
| `AMAP_KEY` | 空 | 后端高德「Web 服务」Key，配置后城市 POI 定位走真实数据 |
| `VITE_AMAP_KEY` / `VITE_AMAP_SECURITY_CODE` | 空 | 前端高德「Web 端(JS API)」Key 与配套安全密钥；写入 `travel-agent-web/.env.local`，Docker 部署时作为 web 构建参数传入 |
| `VITE_FOOTPRINT_BASEMAP` | amap | 足迹地图底图：`amap`（高德栅格 + MapLibre 球体）或 `osm`（原 OSM/OpenFreeMap）。高德瓦片直连商用前需确认授权 |
| `TELEGRAM_BOT_TOKEN` | 空 | Telegram Bot token（@BotFather 创建）。配置即启用该渠道（长轮询），留空渠道关闭 |
| `CHANNEL_WEBHOOK_ENABLED` | false | true 时启用通用 Webhook 渠道入口 |
| `WECHAT_OA_TOKEN` | 空 | 微信公众号服务器配置 Token（明文模式），配置即启用该渠道 |
| `WECHAT_OA_APPID` / `WECHAT_OA_SECRET` | 空 | 公众号客服消息凭据（需已认证公众号）；配置后微信渠道改异步回复，解除被动回复 5s 窗口 |
| `LLM_DAILY_QUOTA` | 0 | 每用户每日 LLM 调用上限（0 不限），超限自动降级规则引擎 |
| `AMAP_MAPS_API_KEY` | 空 | 高德官方 MCP server（`@amap/amap-maps-mcp-server`）的「Web 服务」Key，**与后端 REST 的 `AMAP_KEY` 不是一个变量**；同时需 `MCP_ENABLED=true`。未配置时 MCP 段保持关闭、行为不变 |
| `RAG_CHUNK_VERSION_RETENTION` | 2 | 知识库版本化：除 ACTIVE 版本外额外保留最近 N 个历史版本的 chunk（每日 04:30 清理更老的 INACTIVE 块；ACTIVE 与历史无版本块永不删） |
| `EMBEDDING_ENABLED` | false | true 时 RAG 走真实 Embedding（`EMBEDDING_BASE_URL`/`EMBEDDING_API_KEY`/`EMBEDDING_MODEL`，OpenAI 兼容） |
| `RATE_LIMIT_STORE` | memory | 限流计数存储：`redis` 时多实例共享（需配置 spring.data.redis） |
| `RATE_LIMIT_SESSION` | 60 | `GET /api/auth/me` 的**独立**会话恢复配额（每用户/窗口）。与登录桶（`RATE_LIMIT_AUTH`，默认 10/IP）分开，否则刷新几次页面就会被 429 踢回登录页 |
| `CONVERSATION_HISTORY_LIMIT` | 10 | 喂给 LLM 的会话历史**消息条数**（不是轮数；一轮问答 = 2 条 ≈ 5 轮），参与意图识别/指代消解 |
| `CONVERSATION_RETENTION_DAYS` | 7 | 会话消息保留天数；写入侧每小时快路径 + 每日 04:30 定时兜底清理 |
| `PHOTOS_STORE` | local | 照片存储：`s3` 时用 S3 兼容对象存储（`travel.photos.s3.*`：endpoint/region/bucket/access-key/secret-key） |
| `CHANNEL_WEBHOOK_SECRET` | 空 | Webhook 渠道密钥（X-Webhook-Secret 头，恒时比较）；未配置时端点始终 401 |
| `MCP_ENABLED` | false | true 时启动接入 `travel.mcp.servers` 里的 MCP server（见 §4.10 与 application.yml 示例） |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | 允许的前端来源 |
| `H2_CONSOLE_ENABLED` | false | 是否启用 H2 控制台 `/h2-console`。默认**关闭**（控制台以 `sa` 空密码直连数据库，暴露即等于匿名读写全库）；本地调试需显式设 `true`，生产必须保持 false |
| `RATE_LIMIT_TRUSTED_PROXIES` | 空 | 限流客户端 IP 的受信代理（精确 IP 或 IPv4 CIDR，逗号分隔）。默认空 = **不信任任何代理、完全不读 `X-Forwarded-For`**（否则换伪造 XFF 即可绕过登录限流）；仅后端只能经反代访问时配置，docker compose 默认 `172.16.0.0/12` |
| `APP_TIMEZONE` | `Asia/Shanghai` | 应用时区。代码里的「今天/明天」与日报、记账日期都基于它；容器默认 UTC 时必须显式确认 |
| `TESSERACT_CMD` | 空 | 图片发票 OCR 的 Tesseract 可执行文件路径；不配置时 PDF 电子发票与粘贴文本仍可正常识别 |
| `TESSDATA_PREFIX` | 空 | Tesseract 语言包目录，需包含 `chi_sim.traineddata` 与 `eng.traineddata`；仅在语言包装在非默认目录时需要 |
| `INVOICE_DIR` | `data/invoices` | 上传的发票原始文件目录（已 gitignore；`INVOICE_STORE=s3` 时改为对象存储，连接配置复用 `travel.photos.s3.*`） |
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

### 6.4 切换 MySQL（本地开发）

```bash
docker compose -f docker-compose.infra.yml up -d   # 启动 MySQL（自动执行 sql/init.sql）与 Redis
cd travel-agent-server
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

### 6.5 Docker 一键部署（全栈）

```bash
JWT_SECRET=$(openssl rand -base64 48) docker compose up -d --build
# 打开 http://localhost:8081 （web/nginx 反代 /api 到后端）
```

- 三个服务：MySQL 8.4（healthcheck 门控）→ 后端（Spring Boot mysql profile，镜像内置
  fonts-noto-cjk 供路书 PDF）→ 前端 nginx（SPA 回退 + /api 反代，SSE 已关闭缓冲）；
- 数据持久化卷：`mysql-data`、`backend-data`（H2 兜底文件/照片/发票均落 `/app/data`）；
- 密钥与开关全部经环境变量注入：`JWT_SECRET` 必配；`DEMO_ACCOUNT` 默认 false；
  `LLM_API_KEY` / `AMAP_KEY` / `WEATHER_REAL` / `TELEGRAM_BOT_TOKEN` 等可选（留空功能降级）；
  行程地图需另配 `VITE_AMAP_KEY` / `VITE_AMAP_SECURITY_CODE`（compose 会作为 web 构建参数传入）；
- 本机未安装 Docker 时无法本地验证镜像构建，首次部署请留意 `docker compose build` 输出。

## 7. 测试与 CI

```bash
# 后端：集成测试（内存 H2，不访问网络、不调用真实模型）
cd travel-agent-server && mvn test

# 前端：类型检查 + 单元测试 + 生产构建
cd travel-agent-web && npm run typecheck && npm test && npm run build
```

当前覆盖（后端 394 个 / 前端 166 个 / e2e 5 个，以 CI 输出为准）：

- 认证与授权：401 契约、跨用户读写与删除行程；
- Agent 计划落地：ADD_ITEM / MODIFY_ITEM / REMOVE_ITEM、跨行程操作守卫、确认提交与撤销（含撤销新建行程）；
- V2.2 自主闭环：工具调用循环（去重/预算/取消/非法输出自纠/白名单）、运行追踪（任务链 + 工具调用 + 归属校验）、
  确认后三模块同步（幂等/部分失败/级联清理）、超预算确定性精简、逐日路线段；
- **FR-06 审核改变结果**：转场/天气硬约束判定、确定性重规划（雨天换室内/精简/后移）、
  修不好时确认门要求 `acknowledgeIssues`（未勾选被拒且方案不被消费）；
- **FR-01 需求槽位**：缺目的地/天数/人数先合并追问、跨轮补槽、拒绝回答才兜底且兜底值明示、
  人数贯通行程/预算/路书（酒店双人间计房数、餐饮门票按人头）；
- **FR-02/FR-04 任务图**：依赖边落库可查（PLAN→INTENT、PREVIEW/REVIEW→PLAN）、PREVIEW 与 REVIEW 并行
  （时间区间重叠、任务行顺序稳定）、前置未完成拒绝启动并记 CONFLICT、回收重派只产生一份结果、
  delegate/recover/conflict 三类过程事件；
- 工具层契约：只读工具才允许注册、风险评估等级、业务错误原样透出；
- 理解精度：日期→行程天映射、意图路由（闲聊不被误写成日程）、时间冲突检查（含 MODIFY）；
- 数据一致性：删除行程的级联清理、记账分项与总支出对账；
- 扩展能力：路书 PDF（内容 + 越权）、MCP 桥接（HTTP/stdio 传输、注册与调用、坏服务器跳过）、
  多渠道入口（Webhook 密钥契约、Telegram 消息→引擎→回复与账号自动开通、微信公众号签名/XML 被动回复）、
  POST 流式端点（401/超长校验/长文本建流）；
- 运行环境：应用时区固定；前端流式状态机与 SSE 中断路径；
- **V2.2 自主 Agent**：工具循环（去重/预算/白名单/取消/降级）、运行追踪查询与归属隔离、
  确认门内容指纹、确认后三模块同步与部分失败重试、超预算确定性收敛、路线来源标记与逐日路线段、
  **审核有限重规划与显式风险接受（FR-06）**、**时间线转场行与分段路线（FR-07）**。

CI（[.github/workflows/ci.yml](.github/workflows/ci.yml)）在 push / PR 时执行 `mvn -B verify`、
`npm ci && npm run typecheck && npm test && npm run build`，以及 e2e job（H2 后端 + preview 4173 +
Playwright chromium：登录建行程路书、PWA 资源、结构化规划表单攻略/从零闭环）。

## 8. 后续规划

已完成的工程化改造：密钥外置、版本控制、测试与 CI、原子化提交、参数校验、错误语义对齐、前端路由懒加载与分包、
3D 足迹地图、多轮会话记忆、方案文案 LLM 润色、真实天气（Open-Meteo）、httpOnly Cookie 登录态、Flyway 增量迁移、
SSE/照片短期票据（票据分用途，仅凭票据=只读会话）、接口限流、待确认方案落库（多实例可用）、
数据库分页与聚合下推（5 个列表端点 + `pagination` 契约）、**schema 单一权威**（V1 基线 + `ddl-auto=validate`）、
**高德 MCP 工具接入**（stdio，12 个工具）、**暗色模式 / 390px 移动端 / PWA 离线查看行程**、
`agent.stage` 与 `sse.disconnects` 指标（断连即取消后续阶段）、路书 PDF 导出、
多渠道入口（Telegram / 微信公众号 / 通用 Webhook）、MCP 外部工具生态、LLM 结构化规划（模型选实体编排 +
规则校验兜底 + 失败带反馈重试）、LLM 每日配额（含用量展示）、真实 Embedding（失败锁定降级）、
限流 Redis 计数与照片/发票 S3 兼容存储（多实例）、知识库指纹增量同步与版本化（可审计可回滚）、按天预算偏差视图、
Spring Boot 3.5、全栈 Docker 化，以及若干轮逻辑缺陷修复与安全加固
（详见 [docs/CHANGELOG.md](docs/CHANGELOG.md)）。

**V2.2 自主 Agent 闭环（第十二批）**：Agent 运行态追踪（run/task/tool_call）、自主工具调用循环、
确认门内容指纹、确认后日程/预算/足迹幂等同步（部分失败可重试）、真实路线贯通与来源标记、
超预算确定性收敛、逐日路线段端点。逐 FR 状态与已知差距见
[docs/guide/v2.2-iteration.md](docs/guide/v2.2-iteration.md)。

**审核真正改变结果 + 路线段进时间线（第十三批）**：`ReflectAgent` 新增转场/天气硬约束，
`PlanReviewLoop` 在 `AgentState.REPLAN` 上做**有限（≤2 次）确定性重规划**，仍修不好则确认门要求
`acknowledgeIssues=true`（前端勾选后才可提交）；`route-legs` 接到时间线（转场行）与地图（分段线，
估算虚线/实测实线）。顺带修复真实天气 URL 的 timezone 双重编码（此前 `WEATHER_REAL=true` 总是静默降级模拟）。

**需求槽位与最少追问（第十四批）**：缺「目的地/天数/人数」时不再拿默认值猜——`RequirementSlots` 先做一次
合并追问，用户答多少补多少（跨轮续槽），拒绝回答才按默认值继续且**明示兜底项**；`trip.party_size`（V17）
把人数贯通到预算：酒店双人间计房数、餐饮/门票按人头、交通整车不放大；路书与行程页同步展示人数/房数。

**任务图与委派（第十五批，FR-02/FR-04）**：阶段序升级成确定性任务图
`INTENT → PLAN → {PREVIEW ‖ REVIEW} → (REPLAN → REVIEW)*`——依赖边落库可查（V18 `agent_task.depends_on`），
`PREVIEW` 与 `REVIEW` 并行执行，审核结论委派 `REPLAN` 任务、复审依赖它，重规划后的 Diff 由预览重派重建；
前置未完成拒绝启动并记 `CONFLICT`，回收重派只产生一份结果；`delegate` / `recover` / `conflict`
三类事件进 SSE 过程时间线，运行记录抽屉展示依赖与尝试次数。编排规则由引擎写死、模型不参与调度（ADR-011）。

按优先级继续推进：

**P0 产品力（下一迭代首选：真实数据源联调）**
- 高德 MCP 已接成一等配置（`travel.mcp.servers`，12 个 `mcp_amap_*` 工具 + Windows `npx` 兼容 + 失败降级），
  见 [docs/guide/mcp-amap.md](docs/guide/mcp-amap.md)；**还差后端「Web 服务」Key 做真实 POI/路径/GCJ-02 联调**，
  拿到 Key 后按该文档「真实 Key 联调（一键验证）」跑 `.tools/amap-real-key-probe.ps1` 即可产出证据；
  酒店/餐厅开放平台同理由同一套 MCP 配置扩展（天气已接 Open-Meteo）。

**P1 数据层与部署可信度（第十批已完成主体）**
- schema 单一权威已落地：`V1__baseline.sql` + `V13` 双方言补齐 + 两个 profile `ddl-auto=validate`；
  已在真实 MySQL **8.0.46** 上验通「全新库 / 存量库升级 / V8 幂等」三条路径（8.4 未实机）；
- 知识库不变量已落地：ACTIVE 唯一（MySQL 生成列 + 唯一索引）、旧版本块保留窗口与每日清理、
  `doc_key` 唯一键消除并发首建重复入库；
- **仍待办**：MySQL 8.4 实机回归；极端旧库的列类型（`health_record.weight` / `operation_history.*_state`）
  若从未被旧 `ddl-auto` 收敛过，需要手工 `MODIFY`。

**P2 体验与运营（第十批已完成）**
- 可观测性：`agent.stage{intent|plan|tool|reflect|commit}` Timer、`sse.disconnects{complete|timeout|error|cancelled}`；
- 会话历史已进规划 Prompt（最近 4 条、每条 120 字，仅 LLM 路径）；
- 暗色模式、390px 移动端无横向溢出（7 个场景实测）、PWA 离线查看行程（只读三条 API 走 NetworkFirst）；
- **仍待办**：Safari/iOS 真机与 PWA、echarts/maplibre 暗色配色、二期工具
  （compare_hotel / check_opening_hours / search_ticket）。
