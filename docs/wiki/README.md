# Travel Agent Code Wiki

> 本 Wiki 是对 `travel-agent` 仓库代码的结构化导览，面向需要快速理解代码库的新开发者与 AI 协作者。
> 内容基于代码事实整理（生成日期 2026-11-25），与 `docs/guide/` 下的事实文档互补：guide 面向使用与运维，wiki 面向代码阅读与改造。

## 文档目录

| 篇章 | 内容 |
| --- | --- |
| [01 · 整体架构](01-architecture.md) | 系统分层、核心闭环、请求生命周期、技术选型 |
| [02 · 后端模块](02-backend-modules.md) | `travel-agent-server` 各包职责与关键类清单 |
| [03 · Agent 引擎](03-agent-engine.md) | 状态机、四类 Agent、工具系统、Human-in-the-loop、降级链 |
| [04 · 前端结构](04-frontend.md) | `travel-agent-web` 目录、路由、状态管理、API 层、关键组件 |
| [05 · 数据模型与依赖](05-data-model-and-dependencies.md) | 实体关系、Flyway 迁移、内外部依赖矩阵 |
| [06 · 运行与部署](06-run-and-deploy.md) | 本地开发、测试、Docker 全栈、配置项速查 |

## 30 秒速览

- **这是什么**：以旅行为唯一核心场景的 AI 智能旅行助手（行程规划 → 行中调整 → 行后回顾），附记账、备忘、日程、照片、知识库、足迹。
- **技术栈**：Spring Boot 3.5.16 / Java 17（后端）· React 18 + TypeScript + Vite 6（前端）· H2（默认）/ MySQL 8.4（`mysql` profile）· Flyway 管理 schema。
- **核心闭环**：`自然语言 → Agent 规划 → 工具执行 → RAG 引用 → Plan Diff → 用户确认 → 落库 → 打卡/回顾/撤销`。
- **默认口径**：不配任何 Key 时是「规则规划器 + 内置目录 + 本地估算」；LLM、语义 Embedding、真实 POI/路线、S3、Redis 均为显式开启的增强项；天气默认走 Open-Meteo 真实数据，拿不到明确拒绝、绝不模拟。

## 代码地图

```
travel-agent/
├── travel-agent-server/            # 后端 Spring Boot 应用
│   └── src/main/java/com/travel/agent/
│       ├── agent/                  # ★ Agent 引擎（Intent/Planner/Executor/Reflect + 状态机）
│       ├── tool/  tool/impl/       # 只读工具抽象与 9 个内置工具
│       ├── rag/                    # 检索增强（向量存储、Embedding、版本化知识库）
│       ├── llm/                    # OpenAI 兼容客户端 + 规则降级
│       ├── mcp/                    # MCP（Model Context Protocol）工具桥接
│       ├── channel/                # Telegram / 微信公众号 / Webhook 多渠道入口
│       ├── controller/  service/   # REST 端点与业务服务
│       ├── entity/  repository/    # JPA 实体与数据访问
│       ├── security/               # JWT、登录 Cookie、限流
│       ├── config/  dto/  model/  exception/  observability/  util/
│   └── src/main/resources/
│       ├── application.yml         # 全部可调配置（含降级语义注释）
│       └── db/migration*/          # Flyway V1..V24（H2/MySQL 方言分组）
├── travel-agent-web/               # 前端 React 应用
│   └── src/
│       ├── api/                    # axios 封装 + OpenAPI 生成类型
│       ├── pages/                  # 15 个路由级页面（懒加载）
│       ├── components/             # 30+ 领域组件（ChatPanel/PlanDiff/MapView/Timeline…）
│       ├── store/                  # zustand 状态（agent/auth/trip/theme/ui…）
│       └── hooks/  lib/  styles/  types/
├── docker-compose.yml              # 全栈一键部署（mysql + redis + backend + web/nginx）
├── docker-compose.infra.yml        # 仅基础设施（本地 MySQL/Redis 开发用）
└── docs/                           # 事实文档（guide/review/proposals/legal/operations）
```

## 阅读路径建议

1. 想理解「一句话如何变成一份行程」→ [01](01-architecture.md) → [03](03-agent-engine.md)
2. 想改后端某个接口 → [02](02-backend-modules.md) 找到包 → [05](05-data-model-and-dependencies.md) 看实体
3. 想改前端页面 → [04](04-frontend.md)
4. 想把项目跑起来 / 部署 → [06](06-run-and-deploy.md)
