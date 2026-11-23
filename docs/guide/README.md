# AI 智能旅行助手 · 项目文档

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.12 / Node 20） | 最后验证：2026-11-19 | 事实来源：README.md、travel-agent-server/pom.xml、travel-agent-web/package.json、docs/guide/

以旅行为唯一核心场景的 AI 智能旅行助手：行前规划、行中动态调整、行后回顾；记账、备忘、日程、照片与知识库都归属旅行域。办公自动化、学习辅助与健康数据已从产品主线移除。
本目录是面向**开发者、集成者与运维**的完整文档套件；项目定位与功能全貌请先看根目录 [README.md](../../README.md)。

---

## 产品与工程总览

- [产品开发文档](../PRODUCT_DEVELOPMENT.md)：产品定位、范围、完成度、路线图和验收标准。
- [项目后续工作与上线路线图](../PROJECT_ROADMAP.md)：2026-11-22 当前完成度、P0/P1/P2 待办、上线分级和 UI 改造方案。
- [开发手册](../DEVELOPER_HANDBOOK.md)：环境、代码边界、Agent/工具/RAG/数据库/测试和排障规范。
- [项目改进审计](../review/2026-11-19-project-improvement-audit.md)：基于当前源码与构建结果整理的 P0/P1/P2 改进项、证据和验收标准。

## 文档地图

按「我现在的角色」选入口：

| 角色 / 目标 | 推荐阅读顺序 |
| --- | --- |
| **新加入的开发者** | [代码结构手册](code-structure.md) → [快速上手](getting-started.md) → [架构](architecture.md) → [数据模型](data-model.md) → [开发约定](../DEVELOPMENT.md) |
| **前端开发者** | [快速上手](getting-started.md) → [前端结构](frontend.md) → [API 参考](api-reference.md) |
| **后端 / Agent 能力开发** | [架构](architecture.md) → [API 参考](api-reference.md) → [数据模型](data-model.md) → [开发约定](../DEVELOPMENT.md) |
| **第三方集成 / 对接接口** | [API 参考](api-reference.md)（含认证、票据、错误码） |
| **部署与运维** | [部署与运维](deployment.md) → [快速上手](getting-started.md) 第 2 节 |
| **想知道「为什么改成这样」** | [CHANGELOG](../CHANGELOG.md) → [开发约定](../DEVELOPMENT.md) 末尾的加固批次说明 |

---

## 篇目索引

| 文档 | 内容概要 | 适合谁 |
| --- | --- | --- |
| [capability-boundaries.md](capability-boundaries.md) | **默认口径与能力边界**：无 Key 时的规则规划器 / 目录 / 估算、可选增强项、天气硬契约、RAG 哈希基线、发布门槛现状与对外表述守则 | 所有人（介绍或验收本项目前读） |
| [code-structure.md](code-structure.md) | 代码结构手册：分层与依赖方向、16 个包职责、关键协作类（Agent 引擎 / 联网城市数据拆分）、新增代码落点决策表、命名约定、测试组织、维护红线（单类行数阈值等） | 所有人（写第一行代码前读） |
| [getting-started.md](getting-started.md) | 环境要求、`run-dev.ps1` 启动、首次启动自动完成的事、前端启动与 `/api` 反代、演示闭环 walkthrough、常见问题排查表、提交前自检清单 | 所有人（第一天读） |
| [architecture.md](architecture.md) | 分层架构、Agent 状态机、Intent/Planner/Executor/Reflect 四 Agent、AgentPlan 与 PlanOperation、三级风险 Human-in-the-loop、工具层只读闸门与 9 个内置工具、RAG 与 Embedding 降级、规划期知识引用、行中锁定与旅行事件、旅行回顾聚合、SSE 事件协议与 focus、会话记忆、待确认方案落库、MCP 生态、LLM 配额 | 后端 / Agent 能力开发 |
| [api-reference.md](api-reference.md) | 通用约定（认证、短期票据、统一响应、错误码）、按业务模块逐接口的真实契约（含 `DELETE /api/auth/me` 注销账号）、66 个接口总表 | 前端 / 第三方集成 |
| [data-model.md](data-model.md) | ER 总览、全部实体与表结构（字段 / 类型 / 索引 / 约束）、Flyway 迁移策略、数据一致性不变量、引导数据、常用 SQL 手册 | 后端 / 数据 |
| [frontend.md](frontend.md) | 技术栈、目录结构、路由表（含 `/review`、`/trip/:id/review`）、页面清单、zustand 状态管理、API 层与 SSE 消费链路、核心组件、高德地图 Provider 抽象、构建与测试 | 前端 |
| [deployment.md](deployment.md) | 部署拓扑、Docker 全栈部署步骤、**生产必改项清单**、环境变量权威表、H2/MySQL 切换、卷与备份恢复、反代与 HTTPS、监控与排障、CI 与发布 | 运维 |
| [mcp-amap.md](mcp-amap.md) | 高德 MCP：包身份与两个 Key 的区别、开启步骤、12 个工具与 `mcp_amap_*` 注册名、语义适配层、排障与降级、已验证/未验证边界 | 后端 / 运维 |
| [v2.2-iteration.md](v2.2-iteration.md) | V2.2 迭代记录：逐 FR 落地对照（FR-01~FR-12）、ADR（追踪独立事务 / 循环可降级 / 双指纹确认门 / 同库幂等同步 / 预算快照红线 / 路线来源如实标记 / 超预算确定性收敛）、新增 API、已知差距与下一目标 | 后端 / 前端 / 产品（评估当前能力边界时读） |
| [documentation.md](documentation.md) | 文档生命周期规范：guide / review / proposals / README 的分工，元数据模板，新增 / 更新 / 过期 / 归档规则，`docs-check` 校验命令与提交前自查 | 所有人（改文档前读） |

质量与运维门禁（本轮新增）：

| 文档 | 内容概要 | 适合谁 |
| --- | --- | --- |
| [quality-gates.md](quality-gates.md) | 后端门禁：`mvn verify` 的 JaCoCo 基线/阈值（LINE 79.20%→0.76 等）、Checkstyle 规则与存量豁免 ratchet、SpotBugs Max/High、UTF-8 口径、Redis repository 告警与 PDF 依赖迁移 | 后端 / 评审 |
| [frontend-quality.md](frontend-quality.md) | 前端门禁：ESLint flat config 取舍、Prettier、Vitest 覆盖率双阈值（全局 + store/api/lib 核心目录）与 ratchet、豁免流程 | 前端 |
| [ci.md](ci.md) | GitHub Actions 每个 job 的职责、本地等价命令、失败处置、破坏性 API 变更（oasdiff）、gitleaks/Trivy/dependency-review、豁免流程与 Dependabot 策略 | 所有人（提 PR 前读） |
| [mysql-migration.md](mysql-migration.md) | 真实 MySQL 8.4 上的 Flyway 全量迁移 + Hibernate `validate` 验证：本地一键脚本、CI service container、断言清单、排障（含 8.4 版本警告） | 后端 / 运维 |
| [api-contract.md](api-contract.md) | OpenAPI 作为机器契约：`/v3/api-docs` → `docs/api/openapi.json` → 前端生成类型的链路、漂移检查、`oasdiff breaking` 破坏性变更流程 | 后端 / 前端 / 集成方 |
| [external-dependencies.md](external-dependencies.md) | 高德 / LLM / Embedding / 天气 / Redis / S3 的超时预算、重试（没有就写 0）、失败语义（天气不降级、明确拒绝）、用户提示、`external.degraded{provider,reason}` 指标与对应测试类 | 后端 / 运维 / 值班 |
| [observability.md](observability.md) | 指标字典与基数纪律、13 条 SLO、PromQL、Prometheus 抓取与鉴权、日志字段约定、Grafana Dashboard 与告警清单 | 后端 / 运维 / 值班 |
| [frontend-performance.md](frontend-performance.md) | 地图引擎从 Rollup import 图移出的方案与 before/after 数字（maps chunk 1068 kB → 0，precache 2.99 MiB → 1.97 MiB）、包体预算脚本与 nginx `.mjs` 配置要求 | 前端 / 运维 |

运维与合规（P0 上线阻断项交付）：

| 文档 | 内容概要 | 适合谁 |
| --- | --- | --- |
| [../operations/README.md](../operations/README.md) | 运维与合规文档总索引：runbook、告警规则、生产配置、备份恢复、仓库安全设置、脚本 | 运维 / 值班 / 发布负责人 |
| [../operations/production-config.md](../operations/production-config.md) | 生产环境变量清单：必填/默认/示例/是否密钥、密钥权限与轮换、未配置时的预期行为、`/api/system/status` 判读、compose 透传核对与例外项 | 运维 / 后端 |
| [../operations/backup-restore.md](../operations/backup-restore.md) | MySQL 8.4 逻辑备份/恢复、Flyway 迁移前快照与 V23 后复跑、照片/S3 备份、校验与 RTO/RPO 记录模板、回滚边界 | 运维 / DBA |
| [../operations/github-security-settings.md](../operations/github-security-settings.md) | GitHub 依赖图 / Dependabot / Code scanning / Secret scanning / 分支保护的人工清单，`dependency-review` 转硬门禁条件 | 仓库管理员 |
| [../operations/runbook.md](../operations/runbook.md) | 告警处置手册；新增附录：日志脱敏核对、限流与 LLM 配额验证、告警矩阵补充 | 值班 |
| [../operations/launch-checklist.md](../operations/launch-checklist.md) | 上线执行清单（Android 真机口径）：12 项阻断差距、阶段 0–3 步骤、Android 真机验收矩阵、风险登记 | 发布负责人 / 前端 / 运维 |
| [../legal/README.md](../legal/README.md) | 合规文档索引：隐私政策、第三方服务、数据删除、许可证 | 产品 / 法务 / 发布负责人 |
| [../legal/data-deletion.md](../legal/data-deletion.md) | 账号注销 API（`DELETE /api/auth/me`）的语义、删除范围、运维手工 SQL 与验证清单；旧 JWT/同名重注册已由 V24 收口（`uid` + `token_version`） | 后端 / 运维 / 法务 |
| [../legal/licensing.md](../legal/licensing.md) | 专有许可现状与切换开源许可证的流程（业务决策，不擅自改） | 产品 / 法务 |

补充材料：

| 文档 | 内容 |
| --- | --- |
| [../DEVELOPMENT.md](../DEVELOPMENT.md) | 开发与配置指南：环境变量、测试约定、关键不变量（错误语义、工具层只读、事务边界、撤销语义、日期映射、RAG 过滤、记账对账、预算口径、确认版本、意图路由、会话记忆、URL 凭证、照片安全…）、设计系统 |
| [../CHANGELOG.md](../CHANGELOG.md) | 行为变化记录（含影响使用习惯的调整） |
| [../../README.md](../../README.md) | 项目定位、功能全貌、快速启动、核心设计概要、API 速查、后续规划 |
| [../design/preview/](../design/preview/) | 静态设计预览（`index.html` + `preview.css`），用于对齐视觉方向 |

---

## 一分钟建立心智模型

```
自然语言 → Intent（意图）→ Plan（操作列表，不落库）→ Preview（Diff + 风险分级）
        → 用户确认 → Commit（单事务：Service → Repository + 操作历史）→ 可撤销
```

三条不变量决定了整个系统的形态：

1. **工具层只读**：`ToolRegistry` 拒绝注册任何写操作工具（`readOnly()==false`）。
   任何「会改数据」的能力都必须表达为 `AgentPlan` 的操作项，经用户确认后才落地——
   因此不存在「工具绕过确认直接改库」的路径。
2. **写入只有一个入口**：`ExecutorAgent.commit` 是唯一的落库入口且事务化，行程变更与操作历史同生共死。
3. **确认前可失效**：待确认方案记录生成时的行程指纹（日期 + 预算 + 全部条目），确认时若与当前行程不一致
   则方案失效并要求重新生成，避免多标签页按过期 Diff 覆盖新数据。

细节、代码位置与边界条件见 [architecture.md](architecture.md)。

---

## 模块速览

### 后端（`travel-agent-server`，Spring Boot 3 / Java 17，222 个主代码文件 / 16 个包）

| 包 | 文件数 | 职责 |
| --- | --- | --- |
| `agent` | 29 | Agent 引擎：状态机、四个 Agent、上下文、计划、会话记忆、待确认方案、意图答复协作类 |
| `tool` | 14 | `AgentTool` 契约、`ToolRegistry` 只读闸门、`SafetyInterceptor` 风险分级、`ToolInvoker`、9 个内置只读工具 |
| `rag` | 15 | Embedding（本地哈希 / OpenAI 兼容）、内存向量库、RAG 检索、引用、知识库引导与版本化 |
| `llm` | 7 | 统一 LLM 接口（OpenAI-compatible，可切 DeepSeek/Qwen）+ 规则实现 + 委派与降级 |
| `mcp` | 7 | MCP 外部工具桥接（streamable HTTP / 本机 stdio） |
| `controller` | 12 | REST API + SSE 流式接口 |
| `service` | 36 | 行程 / 预算 / 操作历史 / 目录 / 天气 / 路线 / 回顾 / 事件 / 提醒 / 路书 / 存储 / 联网城市数据 |
| `entity` / `repository` | 19 + 19 | JPA 实体与数据访问（另有 6 张已退役旧表不映射） |
| `security` / `config` | 11 + 14 | JWT、Cookie、访问票据、限流、安全配置、CORS、时区、TraceId、配置属性 |
| `channel` | 7 | 多渠道入口：Telegram / 微信公众号 / 通用 Webhook |
| `dto` / `model` / `util` / `exception` | 12 / 8 / 8 / 3 | 传输对象、领域模型、通用工具、全局异常 |

### 前端（`travel-agent-web`，React 18 + TypeScript + Vite，117 个源文件）

| 目录 | 文件数 | 职责 |
| --- | --- | --- |
| `pages` | 54 | 15 个页面目录（各含 `index.tsx`，含 Reviews/TripReview）；Planner/Trip 的视图拆在各自 `components/`；规划表单逻辑 `planForm.ts` 与其测试 |
| `components` | 64 | ChatPanel / MapView(高德 2D/3D) / Timeline / ItineraryCard / PlanDiff / BudgetChart / FootprintMap / ReminderBell … |
| `api` | 18 | axios 封装、SSE 客户端、票据、行程/回顾/知识库等业务接口（含测试） |
| `store` | 15 | zustand：auth / trip / agent / ui / theme（含测试） |
| `types` / `lib` / `hooks` / `styles` / `test` | 7 / 8 / 2 / 11 / 1 | 与后端对齐的 TS 类型（含 review / knowledge）、图表与通知工具、`useIntersection` / `useOnlineStatus`、固定 `@import` 顺序的样式层（`tokens` 令牌 + `shared`/`components`/`animations` + 页面 CSS）、vitest 环境 `setup.ts` |

---

## 文档维护约定

详见 [文档生命周期规范](documentation.md)（分层、元数据模板、过期与归档规则、校验命令）。要点：

- 本目录所有文档以**源码为准**：与代码不一致时以代码为准，并在 PR 中同步修订文档；
- 新增接口 / 表 / 页面时，请同步更新 `api-reference.md` / `data-model.md` / `frontend.md` 的对应章节与文末统计；
- 涉及使用习惯变化（默认行为、错误码、环境变量语义）的改动，请同时写入 [CHANGELOG](../CHANGELOG.md)；
- 文档中出现的类名、方法名、环境变量名必须可在仓库中检索到，禁止臆造；
- 改完文档运行 `node scripts/docs-check.mjs`，链接与命令引用必须全部有效（exit 0）。
