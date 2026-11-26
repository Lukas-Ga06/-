# AI 智能旅行助手（行程规划 · 记账 · 备忘 · 日程 · 知识库 · 足迹回顾）

以旅行为唯一核心场景的 AI 智能旅行助手：行前规划 → 行中调整 → 行后回顾；记账、备忘、日程、照片与知识库都归属旅行域。

**技术栈**：Spring Boot 3.5.16 / Java 17（Agent Engine + RAG + Tools）· React 18 + TypeScript + Vite 6 / Node 20
**核心闭环**：自然语言 → Agent 规划 → 工具执行 → RAG 引用 → Diff → 用户确认 → 落库 → 打卡 / 回顾 / 撤销

> **默认体验先说清楚**：不配置任何 Key 时，产品是「**规则规划器 + 内置目录 + 本地估算**」；
> 大模型、语义 Embedding、实时 POI / 路线、S3 / Redis 都是显式开启的增强项。
> 天气是唯一默认走真实数据、且失败时**明确说排不了**的数据源（不使用模拟天气）。
> 完整口径、数据来源分级与发布门槛见 [能力边界与默认口径](docs/guide/capability-boundaries.md)。

> 办公自动化、学习辅助与健康数据已从产品主线移除；`/office`、`/learning`、`/health` 只保留到 `/` 的兼容重定向。产品定位见 [产品开发文档](docs/PRODUCT_DEVELOPMENT.md)。

## 文档入口

| 我想… | 看这篇 |
| --- | --- |
| 5 分钟跑起来 | [快速上手](docs/guide/getting-started.md) |
| 弄清默认能力和真实数据边界 | [能力边界与默认口径](docs/guide/capability-boundaries.md) |
| 理解架构与状态机 | [架构与核心设计](docs/guide/architecture.md) |
| 查接口字段与错误码 | [REST API 参考](docs/guide/api-reference.md) |
| 改表 / 写迁移 | [数据模型](docs/guide/data-model.md) |
| 改前端页面 | [前端结构](docs/guide/frontend.md) |
| 上线与排障 | [部署与运维](docs/guide/deployment.md) |
| 提交前要过哪些门禁 | [质量门禁](docs/guide/quality-gates.md) / [前端质量](docs/guide/frontend-quality.md) / [CI](docs/guide/ci.md) |
| 看 SLO、告警与故障手册 | [可观测性](docs/guide/observability.md) / [Runbook](docs/operations/runbook.md) |
| 改接口/生成前端类型 | [API 契约](docs/guide/api-contract.md) |
| 验证真实 MySQL 迁移 | [MySQL 迁移验证](docs/guide/mysql-migration.md) |
| 查外部依赖怎么降级 | [外部依赖降级矩阵](docs/guide/external-dependencies.md) |
| 优化首屏/包体 | [前端性能](docs/guide/frontend-performance.md) |
| 新代码放哪 | [代码结构手册](docs/guide/code-structure.md) |
| 接高德 MCP | [高德 MCP 接入](docs/guide/mcp-amap.md) |
| 看 V2.2 迭代账本 | [V2.2 迭代记录](docs/guide/v2.2-iteration.md) |
| 文档怎么写 | [文档生命周期](docs/guide/documentation.md) |
| 全部文档索引 | [docs/guide/README.md](docs/guide/README.md) |

其他：[开发手册](docs/DEVELOPER_HANDBOOK.md) · [开发约定](docs/DEVELOPMENT.md) · [变更记录](docs/CHANGELOG.md) · [评审快照](docs/review/README.md) · [方案契约](docs/proposals/README.md)

## 快速启动

**环境**：JDK 17+、Maven 3.8+、Node 20 / npm（CI 基线为 Node 20；Vite 6 支持 Node 18+）。默认 H2 文件库，**无需安装数据库**。

### 1. 后端（:8080）

```bash
cd travel-agent-server
export JWT_SECRET="$(openssl rand -base64 48)"   # 可选：不设则每次启动随机，重启需重新登录
export LLM_API_KEY="sk-你的key"                  # 可选：不设走规则规划器（主流程完整，无模型增强）
mvn spring-boot:run
```

Windows 推荐 `.\run-dev.ps1`：读取 `travel-agent-server/.env.local`（已 gitignore）并自动固定 `JWT_SECRET`。
首次启动自动完成 Flyway 迁移（V1 → V29）、创建演示账号 **demo / demo123**、加载城市目录与旅游知识库。

### 2. 前端（:5173）

```bash
cd travel-agent-web
npm install
# 可选：cp .env.example .env.local，填 VITE_AMAP_KEY / VITE_AMAP_SECURITY_CODE
npm run dev
```

打开 <http://localhost:5173>，用 **demo / demo123** 登录。前端高德 Key 是「Web 端(JS API)」，与后端 `AMAP_KEY`（Web 服务）不是同一凭据。

### 3. 演示一条闭环（本地 H2，非生产数据）

1. 首页输入「帮我安排一个上海三日游，预算 3000 元，酒店离地铁近一点」→ 进入规划；
2. 聊天面板展示意图 / RAG 引用 / 工具调用；方案完成弹出 **Plan Diff**（原方案 → 新方案、预算对比）；
3. 点「确认修改」→ 行程落库 → 行程主界面（地图 / AI 助手 / 时间轴）；
4. 行中说「明天下雨，把户外景点换掉」→ 只替换 `PENDING` 的室外条目 → Diff 确认。
   这一步依赖**真实天气**：拿不到预报时系统会明确拒绝并说明原因，不会用模拟天气改行程；
   预报是晴天时只按你的意图替换，不会伪造「因为下雨」；
5. 时间轴推进「开始 / 完成 / 跳过」→ 写 `visitedAt` 与旅行事件；
6. 行程页「旅行回顾」看完成率、计划 vs 实际、到访地点与 AI 总结；
7. 右上角撤销：按操作历史 before/after 快照还原上一步（含打卡状态与实际时间）。

> 演示路径里的价格是参考估算、路线在未配高德时为本地估算（界面标注 `estimated`）；
> 它们是**演示可用**的数据，不是实时库存或实时路况。见 [能力边界](docs/guide/capability-boundaries.md)。

分步 walkthrough 与常见问题：[快速上手](docs/guide/getting-started.md)。

## 核心能力

**默认具备（无需任何 Key）**

- **Agent 引擎**：Intent / Planner / Executor / Reflect 四 Agent；审核不通过进入 `REPLAN`（≤2 次）；调度是确定性任务图，模型不参与调度 [架构](docs/guide/architecture.md)。
- **规则规划器**：内置城市目录 + 模板 + 预算测算，生成可确认的方案；不依赖模型也能跑完整闭环。
- **Human-in-the-loop**：工具层只读，所有写操作表达为 `AgentPlan` 操作项，经 preview → Diff → 确认 → commit；LOW / MEDIUM / HIGH 三级风险。
- **可靠确认与续传**：确认提交用租约 + 单事务栅栏（租约失效整体回滚，不重复落库）；日程/预算/足迹同步走事务性 Outbox，进程崩溃后可补投。SSE 事件按 `seq` 落库，断线带 `Last-Event-ID` 自动续传一次，不重新规划、不重复消耗 LLM 配额。
- **行中锁定**：自动重规划只动 `PENDING` 条目，已开始 / 完成 / 跳过的安排不被天气覆盖。
- **预算口径**：`planned` 为计划成本、`actual` 为记账合计；预算同步只记快照，**绝不**自动生成记账。
- **足迹口径**：只有 `visitedAt` 非空的条目算「去过」，未来计划不会写成「已到访」。
- **真实天气**：默认接 Open-Meteo（免费无 Key）；拿不到就明确说「排不了」，**不使用模拟天气**。
- **RAG 引用（词面哈希基线）**：规划期检索知识库并带 `documentId/chunkId/score/source`；默认向量是字符哈希，**不是语义检索**。
- **前端体验**：暗色模式、390px 移动端、PWA 离线查看行程。

**显式开启的增强**

- **大模型**（`LLM_API_KEY`）：意图识别、结构化规划、文案润色走模型；失败回落规则规划器。
- **语义 Embedding**（`EMBEDDING_ENABLED`）：真实语义检索；切换模型必须清库重建。
- **真实 POI / 路线**（`AMAP_KEY`）：`source=amap|estimated` 明确标注；限流/失败时回落估算并自报。
- **多渠道**：Telegram / 微信公众号 / 通用 Webhook 复用同一 `AgentEngine`。
- **导出与多实例**：路书 PDF；Redis 限流计数、S3 兼容照片存储。

> 价格为参考估算（无实时房态/库存）。各项数据来源与失败语义见 [能力边界与默认口径](docs/guide/capability-boundaries.md)。
> V2.2 逐 FR 落地与已知差距：[V2.2 迭代记录](docs/guide/v2.2-iteration.md)。

## 最小配置

| 变量 | 说明 |
| --- | --- |
| `JWT_SECRET` | Base64 密钥（解码后 ≥32 字节）。生产必配；缺省时启动生成一次性随机密钥 |
| `LLM_API_KEY` | 任意 OpenAI-compatible Key；为空全部走规则规划器（主流程可跑，回复来自模板） |
| `LLM_BASE_URL` / `LLM_MODEL` | 默认 `https://api.deepseek.com/v1` / `deepseek-flash` |
| `AMAP_KEY` | 后端高德「Web 服务」Key；配置后城市 POI / 路线走真实数据（失败仍回落估算并标注） |
| `WEATHER_SIMULATION` | 默认 `false`。**仅测试 / 离线演示**可设 `true` 返回模拟天气；生产保持 `false`（状态接口会自报 `simulated`） |
| `VITE_AMAP_KEY` / `VITE_AMAP_SECURITY_CODE` | 前端高德「Web 端(JS API)」Key 与安全密钥（写入 `travel-agent-web/.env.local`） |
| `DEMO_ACCOUNT` | 默认 true；**生产必须 false** |
| `COOKIE_SECURE` | https 部署设 true |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 仅 `mysql` profile 生效 |

完整变量表、密钥降级行为与生产必改项：[部署与运维](docs/guide/deployment.md) · [开发约定](docs/DEVELOPMENT.md)。

### 切 MySQL（本地）

```bash
docker compose -f docker-compose.infra.yml up -d    # MySQL + Redis（自动执行 sql/init.sql）
cd travel-agent-server && mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

### Docker 全栈

```bash
JWT_SECRET=$(openssl rand -base64 48) docker compose up -d --build
# http://localhost:8081 （nginx 反代 /api 到后端）
```

## 测试与 CI

```bash
# 后端：集成测试（内存 H2，不触网、不调真实模型）
cd travel-agent-server && mvn test

# 前端：类型检查 + 单元测试 + 生产构建
cd travel-agent-web && npm run typecheck && npm test && npm run build

# 端到端（隔离后端：独立 H2，LLM/MCP/AMAP 全关；首次会自动构建后端 jar）
cd travel-agent-web && npm run build && npm run e2e:isolated
```

CI（[.github/workflows/ci.yml](.github/workflows/ci.yml)）在 push / PR 执行 `mvn -B verify`、
前端全部门禁，以及基于隔离后端的 Playwright e2e job。隔离后端由
[`scripts/start-e2e-backend.mjs`](scripts/start-e2e-backend.mjs) 启动，不会读取开发者的 `.env.local`。
覆盖范围与测试规范见 [开发手册](docs/DEVELOPER_HANDBOOK.md)；用例数以 CI 输出为准，不在 README 里维护静态计数。

## 项目结构（最小版）

```
travel-agent/
├── travel-agent-server/   # Spring Boot：agent/ tool/ rag/ llm/ controller/ service/ entity/
├── travel-agent-web/      # React + Vite：pages/ components/ store/ api/
├── docs/guide/            # 当前事实文档（架构 / API / 数据 / 前端 / 部署 / 代码结构）
├── docs/review/           # 带日期的评审快照
├── docs/proposals/        # 未落地 / 已落地方案契约
├── scripts/docs-check.mjs # 文档链接与命令校验
└── docker-compose.yml     # 全栈一键部署
```

## 状态与许可证

- 版本 **0.1.0**（`travel-agent-server` / `travel-agent-web` 同步）。
- 文档基线 **2026-11-25**；改文档后运行 `node scripts/docs-check.mjs` 校验链接与命令引用。
- **可发布级别**：当前具备「内部演示」；受控灰度 / 公网 v1 仍缺**真机验收（Android / iOS）**、正式许可与 GitHub 安全门禁等。真实 MySQL 8.4 的全量迁移与备份恢复已于 2026-11-23 实测通过（见 [MySQL 迁移验证](docs/guide/mysql-migration.md)、[备份恢复](docs/operations/backup-restore.md)）。总体口径见 [P0 执行快照](docs/review/2026-11-22-p0-execution.md) §5。
- 仓库采用**专有许可**（见 [LICENSE](LICENSE)）：`Copyright (c) 2026 Travel Agent contributors. All rights reserved.`。**未授予开源许可**；未经版权持有人书面许可，不得复制、分发、修改或用于商业服务。许可证策略与切换开源许可证的流程见 [许可证说明](docs/legal/licensing.md)。
- 隐私政策、第三方服务说明、数据删除流程与许可证说明见 [合规文档索引](docs/legal/README.md)。这些文档在发布前需法务确认；其中**账号注销**（`DELETE /api/auth/me`，需密码二次确认）与**账号级数据导出**（`GET /api/auth/me/export`，JSON 快照，照片只给清单）均已实现，见 [数据删除流程](docs/legal/data-deletion.md) 与 [API 参考](docs/guide/api-reference.md) §2.6。
