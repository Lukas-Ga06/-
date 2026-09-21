# 多模态智能旅行 Agent（Multimodal Intelligent Travel Agent）

面向「行前规划 + 行中动态调整」的智能旅行规划与决策系统。

**技术路线**：Spring Boot 3 + Agent Engine（Intent / Planner / Executor / Reflect）+ RAG + Tools + React 18
**核心交互**：Voice/Text + Map + Timeline + Card
**核心闭环**：自然语言表达意图 → Agent 理解与规划 → Tools 执行 → RAG 提供知识 → GUI 可视化 → 用户确认 → 行程更新

---

## 1. 项目结构

```
travel-agent/
├── sql/init.sql                        # MySQL 初始化脚本（H2 模式无需执行）
├── docker-compose.yml                  # 可选：MySQL + Redis 基础设施
├── travel-agent-server/                # 后端（Spring Boot 3 / Java 17）
│   └── src/main/java/com/travel/agent/
│       ├── agent/                      # Agent Engine：状态机 + 四个 Agent + 上下文 + 计划
│       ├── tool/                       # AgentTool 接口 / ToolRegistry / SafetyInterceptor / 10 个工具
│       ├── rag/                        # Embedding / VectorStore / RagService / 知识库引导
│       ├── llm/                        # 统一 LLM 接口（OpenAI-compatible，可切 DeepSeek/Qwen）
│       ├── controller/                 # REST API + SSE 流式接口
│       ├── service/                    # 行程 / 预算 / 操作历史 / 目录 / 天气 / 路线
│       ├── entity/ repository/         # JPA 实体与数据访问
│       ├── security/ config/           # JWT 认证与安全配置
│       └── exception/                  # 全局异常（含 HumanConfirmationRequired）
└── travel-agent-web/                   # 前端（React 18 + TypeScript + Vite + AntD + ECharts + Leaflet）
    └── src/
        ├── components/                 # ChatPanel / MapView(MapProvider) / Timeline / ItineraryCard / BudgetChart / PlanDiff / VoiceButton
        ├── pages/                      # Home / Planner / Trip（主界面）/ Login / Profile
        ├── store/                      # zustand：auth / trip / agent / ui
        ├── api/                        # axios 封装 + SSE EventSource 客户端
        └── types/                      # 与后端 AgentResult / PlanDiff 对齐的 TS 类型
```

## 2. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17+ | 后端编译运行 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+ | 前端构建（含 npm） |

数据库：**默认使用 H2 文件数据库，开箱即用，无需安装任何数据库**。
要切换 MySQL/Redis 时运行 `docker compose up -d` 并按第 6 节修改配置。

## 3. 快速启动

### 3.1 启动后端（端口 8080）

```bash
cd travel-agent-server
mvn spring-boot:run
```

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

## 4. 核心设计

### 4.1 Agent 状态机

```
RECEIVE → UNDERSTAND → PLAN → EXECUTE → REFLECT → PRESENT → CONFIRM → COMMIT
                                      ↘ ERROR（反馈用户）
```

- **IntentAgent**：LLM 优先 + 规则兜底，输出 `{intent, target, constraints}`；
- **PlannerAgent**：结合行程/预算/偏好/天气/交通生成 `AgentPlan`（操作列表，不直接落库）；
- **ExecutorAgent**：preview（生成 Diff + 确认请求）→ 用户确认 → commit（Service → Repository + 操作历史）；
- **ReflectAgent**：时间冲突、预算超限校验，输出警告。

### 4.2 Human-in-the-loop 三级风险

| 等级 | 操作 | 策略 |
| --- | --- | --- |
| LOW | 查询、推荐 | 自动执行 |
| MEDIUM | 修改行程 | 生成 Diff，确认后提交 |
| HIGH | 超预算等大额调整 | Diff + 醒目警告，确认后提交 |

### 4.3 静态知识 / 实时信息分离

- **RAG**：旅游知识、景点介绍、城市攻略、注意事项（内置 10 篇文档，内存向量库 Top-K 检索）；
- **Tools**：天气 / 路线 / 酒店价格等实时信息（MVP 为确定性模拟数据，接口与真实服务一致）。

### 4.4 SSE 流式协议（GET /api/agent/stream）

事件流：`start → intent → planning → tool_call → tool_result → rag_result → plan_update → focus → confirmation → complete`。
Agent 通过 **GUI Focus 协议** 返回 focus（day/itemId/坐标），前端自动切换 Day、滚动时间轴、地图 flyTo。

### 4.5 一期工具清单（10 个）

`search_attraction` / `search_hotel` / `search_restaurant` / `query_weather` / `calculate_route` /
`calculate_budget` / `get_itinerary` / `add_itinerary_item` / `modify_itinerary_item` / `undo_operation`

## 5. REST API 一览

```
POST   /api/auth/register        POST /api/auth/login
GET    /api/trips                POST /api/trips
GET    /api/trips/{id}           PUT  /api/trips/{id}      DELETE /api/trips/{id}
GET    /api/trips/{id}/itinerary POST /api/trips/{id}/itinerary
PUT    /api/itinerary/{id}       DELETE /api/itinerary/{id}

POST   /api/agent/chat           # 同步对话
GET    /api/agent/stream         # SSE 流式对话（token 走查询参数）
POST   /api/agent/confirm        # 确认提交 planId
POST   /api/agent/cancel         # 取消方案
POST   /api/agent/undo           # 撤销上一次修改
```

统一响应：`{"code":0,"message":"success","data":...}`。

## 6. 配置说明

### 6.1 接入真实大模型（可选）

默认关闭（纯规则引擎完整可运行）。编辑 `travel-agent-server/src/main/resources/application.yml`：

```yaml
travel:
  llm:
    enabled: true
    base-url: https://api.deepseek.com/v1   # 或 https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: sk-xxx
    model: deepseek-chat                     # 或 qwen-plus / gpt-4o-mini
```

模型仅用于意图识别增强与回复润色，关闭时全流程可用。

### 6.2 切换 MySQL

```bash
docker compose up -d                 # 启动 MySQL（自动执行 sql/init.sql）与 Redis
cd travel-agent-server
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
# 环境变量：MYSQL_USERNAME / MYSQL_PASSWORD 可覆盖默认 root/root
```

## 7. MVP 与后续规划

当前实现对应开发文档中的 **MVP 第一版 + Phase 2~7 核心能力**（CRUD、地图/时间轴/预算 GUI、Agent 引擎、Tools、RAG、多模态上下文 selectedElement、Diff/Confirm/Undo/Audit）。

后续可扩展：
- 真实数据源：高德/百度 POI 与路径规划、和风天气、酒店/餐厅开放平台；
- Pgvector / Milvus 替换内存向量库（`VectorStore` 接口已预留）；
- Redis 存储待确认会话与缓存（多实例部署）；
- 语音合成（TTS）、WebSocket 实时状态、RabbitMQ 异步任务；
- compare_hotel / check_opening_hours / search_ticket / generate_trip_summary 等二期工具。
