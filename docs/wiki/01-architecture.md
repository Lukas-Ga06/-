# 01 · 整体架构

## 1.1 系统定位

AI 智能旅行助手是一个 **monorepo** 全栈应用，以旅行为唯一核心场景，覆盖：行前规划（自然语言生成行程）→ 行中调整（天气/意图驱动的重规划、打卡）→ 行后回顾（完成率、计划 vs 实际、AI 总结）。记账、备忘、日程、照片、知识库均归属旅行域。

## 1.2 顶层分层

```
┌────────────────────────────────────────────────────────────┐
│  客户端                                                     │
│  ├── Web SPA (React 18 + TS + Vite, :5173 dev / :8081 prod) │
│  └── 外部渠道：Telegram Bot / 微信公众号 / 通用 Webhook        │
└──────────────┬─────────────────────────────────────────────┘
               │ HTTP/JSON (统一信封 {code,message,data}) + SSE
┌──────────────▼─────────────────────────────────────────────┐
│  travel-agent-server (Spring Boot 3.5, Java 17, :8080)      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ controller  REST 端点（12 个 Controller）              │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ agent      AgentEngine 状态机（核心编排）              │  │
│  │            Intent → Planner → Executor → Reflect      │  │
│  ├──────────────┬───────────────┬───────────────────────┤  │
│  │ tool（只读）  │ rag（检索引用） │ llm（模型调用+降级）    │  │
│  ├──────────────┴───────────────┴───────────────────────┤  │
│  │ service    业务服务（Trip/Itinerary/Budget/Expense…） │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ repository Spring Data JPA                            │  │
│  └──────────────────────────────────────────────────────┘  │
│  security（JWT + Cookie + 限流） · observability（指标/traceId）│
└──────────────┬─────────────────────────────────────────────┘
               │
┌──────────────▼──────────┐  ┌───────────────────────────────┐
│ 数据库                    │  │ 外部服务（全部可降级）           │
│ H2 文件库（默认）          │  │ OpenAI 兼容 LLM（DeepSeek 等） │
│ MySQL 8.4（mysql profile）│  │ OpenAI 兼容 Embedding（可选）  │
│ Flyway V1..V24           │  │ 高德 Web 服务 / 高德 MCP（可选）│
└──────────────────────────┘  │ Open-Meteo 天气（默认真实源）  │
                              │ S3 兼容对象存储（可选）        │
                              │ Redis（可选，限流计数）        │
                              └───────────────────────────────┘
```

## 1.3 核心闭环（Human-in-the-loop）

系统的根本设计原则：**工具层只读，一切写操作必须经用户确认**。

```
用户消息
  → AgentEngine（状态机 RECEIVE→UNDERSTAND→PLAN→EXECUTE→REFLECT→PRESENT）
  → IntentAgent 意图识别（LLM，失败走规则解析）
  → PlannerAgent 生成 AgentPlan（含 PlanOperation 操作项列表）
      ├─ 规划期经 ToolRegistry 调用只读工具（天气/POI/路线/预算/知识库）
      └─ 经 RagService 检索知识库并携带引用 citation
  → ReflectAgent 审核方案（不通过进入 REPLAN，≤2 次）
  → ExecutorAgent.preview() 生成 PlanDiff（ADD/REMOVE/MODIFY + 预算前后对比）
  → 状态 = NEED_CONFIRMATION，返回前端
用户点「确认修改」
  → ExecutorAgent.commit()
  → ItineraryService.applyOperations() 落库（变更前后快照 → OperationHistory）
  → 写 TripEvent，返回完成状态
```

关键推论：

- **LLM 只产出"计划"，不直接改数据**。写路径唯一入口是 `ExecutorAgent.commit` → Service → Repository。
- **撤销能力**来自 `OperationHistory` 的 before/after 快照（含打卡状态与实际时间）。
- **行中锁定**：自动重规划只动 `PENDING` 状态的条目，已开始/完成/跳过的不被覆盖。
- **审核与降级**：LLM 不可用/超时/超配额时逐层降级为规则规划器，且在响应中如实标注。

## 1.4 请求生命周期（普通 REST）

```
HTTP → TraceIdFilter（注入 traceId，日志/响应头同名）
     → RateLimitFilter（按 IP/用户分桶，memory 或 redis 计数）
     → JwtAuthenticationFilter（解析 Bearer / Cookie，注入 UserContext）
     → Controller（@Valid 入参）
     → Service（业务规则、事务）
     → Repository（JPA）
     ← ApiResponse 统一信封（code/message/data）
异常 → GlobalExceptionHandler → BusinessException 映射业务码
```

SSE 路径（Agent 流式对话）：`AgentController` 推送 `AgentEvent`（intent/planning/tool_call/plan_update/confirmation/complete/error/degrade…），前端 `agentStore` 消费。

## 1.5 多渠道架构

`channel` 包把 Telegram Bot（长轮询）、微信公众号（被动回复 5s 窗口，可选客服消息异步）、通用 Webhook（`X-Webhook-Secret` 恒时比较）统一适配到**同一个 `AgentEngine`**，账号体系与数据与 Web 完全一致。

## 1.6 横切设计决策

| 决策 | 实现 | 位置 |
| --- | --- | --- |
| schema 单一权威 | Flyway 迁移，JPA `ddl-auto=validate`（启动缺列直接失败） | `application.yml` |
| 时区统一 | `Asia/Shanghai` 默认，启动时 `TimeZoneConfig.setDefault`，容器 TZ 同步钉住 | `config/TimeZoneConfig` |
| 端到端时间预算 | Agent 主链路 30s deadline，耗尽按 ADR-002 降级并标注 `budget_degraded` | `agent/AgentBudget` |
| LLM 配额 | 每用户每日计数落库 `llm_quota_usage`，超限自动降级 | `service/LlmQuotaService` |
| 幂等 | Agent 写命令去重（`agent_command_dedupe`） | `service/AgentCommandIdempotency` |
| 限流 | 登录/Agent/上传/导出/会话恢复独立桶；`trusted-proxies` 默认空（不信 XFF） | `security/RateLimitFilter` |
| 观测 | Micrometer 指标 + histogram（P95）+ `/actuator/prometheus`（需登录） | `observability/TravelMetrics` |
| 文档即门禁 | `scripts/docs-check.mjs` 校验文档链接与命令引用 | `scripts/` |

## 1.7 技术选型一览

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 后端框架 | Spring Boot 3.5.16 / Java 17 | Web + Validation + Data JPA + Actuator |
| 数据库 | H2（默认文件库）/ MySQL 8.4 | `MODE=MySQL`；Flyway 按 profile 挂方言目录 |
| 迁移 | Flyway V1..V24 | `migration/` 公共 + `migration-h2/`、`migration-mysql/` 方言 |
| LLM | OpenAI 兼容 HTTP 客户端 | 默认 DeepSeek；`DelegatingLlmClient` 统一降级入口 |
| 检索 | 自研内存向量库 | 默认字符 bigram 哈希 Embedding；可选真实语义 Embedding |
| 前端 | React 18 + TS + Vite 6 | antd 5 + zustand 4 + axios + react-router 7 |
| 地图/图表 | maplibre-gl + 高德 JS API（可选）/ echarts 6 | |
| PWA | vite-plugin-pwa | 行程相关 GET API runtime caching，离线可看行程 |
| 测试 | JUnit（后端集成测试）/ Vitest / Playwright | e2e 用隔离后端（独立 H2，LLM/MCP/AMAP 全关） |
