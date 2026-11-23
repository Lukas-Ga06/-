# P0/P1 执行快照：Agent 可靠性、发布门禁、账号注销与 UI 收口（2026-11-22）

> 记录时间：2026-11-22 23:5x（Asia/Shanghai）｜基线 commit `1be23f2` + 本轮工作区改动
> 环境：Windows 11 / JDK 17.0.1 / Maven 3.9.16 / Node 20.17 / Playwright 1.64.1 / H2（MODE=MySQL）
> 结论：**内部演示条件保持；受控灰度仍需真实 MySQL 演练与运营闭环；公网 v1 仍被真实 iOS、法务确认、GitHub 安全设置和真实外部服务验收阻断。**
> 本文只记录“已验证事实”。未执行的项目单独列出，不作为已完成。

## 1. 本轮结论

| 路线图项 | 代码/文档状态 | 自动化证据 | 真实环境状态 |
| --- | --- | --- | --- |
| P0-A 生产配置与外部依赖 | 已完成主体 | 默认模型改为 `deepseek-flash`；启动探测 `/models` 并暴露 `llmModelAvailable/llmModelError/llmProvider`；`/api/system/status` 增加 MCP 状态；`.env.production.example`、`production-config.md`、compose/Redis/S3/时区/安全开关已补齐 | **真实 `/models` 探测通过**（`api.deepseek.com`，模型在清单中）；未用全新生产 `.env` 完整启动，未做 Key 轮换演练 |
| P0-B Agent 可靠性 | 已完成主体 | 内置工具参数 schema + `ToolInvoker` 校验；研究循环 partial 收尾；MCP 10021 退避；多意图可见提示；617 个后端测试通过 | 未用真实模型重复跑 20 次规划；未在真实高德限流下验证 MCP 分支 |
| P0-C 发布门禁 | 已完成隔离与 CI 改造主体 | `npm run e2e:isolated` 独立 H2 + LLM/MCP/AMAP 全关；`isolation.spec.ts` 自证；CI 改用隔离后端；视觉 Linux 基线生成 workflow + 观察 job | 桌面 14/14、设备 22/22 本地通过；GitHub Dependency graph/Code scanning 仍需人工开启；`e2e-device` 仍观察；Linux 视觉基线尚未生成 |
| P0-D 数据、安全与运营 | 部分完成 | 账号注销 API + Profile 入口 + 级联删除测试；备份/恢复 runbook 与 `backup-drill.ps1`；隐私/第三方/删除/许可证文档；nginx 访问日志去票据；compose 日志轮转 | **未在真实 MySQL 8.4 做备份恢复演练**；未跑 V23 后真实迁移；法务占位待确认；旧 JWT 同名重注册风险未修 |
| P1-E 运行反馈 | 已完成主体 | 新增 `RunStatus`（loading/empty/degraded/partial/failed/cancelled/complete），接入 Agent Activity、执行结果、聊天面板 | 仅本地组件/单测与截图，无真实 provider 失败注入验收 |
| P1-F 真实 iOS Safari | 未完成 | 仅 Playwright WebKit + iPhone 预设（模拟） | 无真实 iPhone 设备；这是公网 v1 的硬阻断项 |
| P1-G 视觉系统与高频页面 | 已完成主体 | `global.css` 5,101 行拆为 6 个职责文件；Emoji→Ant 图标；首页空足迹、助手入口、行程详情、移动 Tab、攻略库文案；视觉 11/11 | 视觉基线为 Windows；真实 iPhone safe-area/软键盘未验 |
| P1-H 真实检索质量 | 未完成 | 仍为本地哈希 Embedding 基线 | 未接真实 Embedding provider；真实高德跨城/route polyline/GCJ-02 未复验 |
| P2 OTA/个性化/高级整理 | 未开始 | — | 不阻塞 v1，按路线图后置 |

## 2. 关键改动

### 2.1 后端（travel-agent-server）

- `tool/AgentTool.java` + `tool/ToolArgs.java` + `tool/ToolInvoker.java` + 9 个 `tool/impl/*`：
  结构化参数声明、决策提示渲染、未知/缺失参数校验、数字/布尔/列表宽容解析。
- `agent/AgentToolLoop.java`、`agent/PlannerAgent.java`：已有成功观察时按 `partial=true` 收尾，
  不再把“步数用满”记成研究失败；研究步数上限 4 → 6，仍受总预算门禁。
- `service/AmapDataService.java`：MCP 分支遇到 `10021 / CUQPS_HAS_EXCEEDED_THE_LIMIT`
  时退避 220–400ms 重试一次，仍失败退回 REST；日志 WARN 不刷 ERROR。
- `llm/LlmModelProbe.java`（新）、`llm/LlmProperties.java`、`controller/SystemController.java`：
  启动后异步探测模型清单；`/api/system/status` 新增 `llmProvider/llmModelAvailable/llmModelError/
  llmModelCheckPending/mcpEnabled/mcpConnected/mcpToolCount/mcpServers/mcpLastError/mcpHint/amapMcpToolCount`；
  不含任何密钥。
- `agent/IntentResult.java`、`agent/IntentAgent.java`、`agent/MultiIntentNotices.java`（新）、
  `agent/AgentEngine.java` 及两个 handler：同一条消息里的其他独立请求进入 `alsoRequested`，
  在 REST/SSE/落库三处各提示一次“这次没有执行”，前缀幂等。
- `service/AccountDeletionService.java`（新）、`controller/AuthController.java`、`dto/AuthDtos.java`：
  `DELETE /api/auth/me`（密码确认）事务级联删除本人数据；照片文件在事务提交后清理。
- `src/main/resources/application.yml`：默认 `LLM_MODEL=deepseek-flash`，新增模型探测开关与注释。

### 2.2 前端（travel-agent-web）

- `src/styles/global.css` 从 5,101 行收敛为 13 行入口；新增
  `tokens.css / shell.css / buttons.css / cards.css / status.css / pages.css`，跨文件重复选择器为 0。
- `src/components/RunStatus/`（新）：统一状态组件（图标 + 文字 + 可选操作），接入
  `AgentActivityPanel`、`ExecutionResultPanel`、`ChatPanel`。
- Emoji → `@ant-design/icons`：导航、页面标题、地图/足迹控制器、AI 头像、空状态、结果面板、
  攻略库、Toast/Undo/OfflineBanner 等；纯图标按钮补 `Tooltip`/`aria-label`。
- 首页：无足迹不渲染地图，80–96px 紧凑空状态；地图失败/数据失败分开；4 个任务入口。
- AI 助手：3–4 个任务入口、空态限高、输入区 sticky + safe-area；移动端仅隐藏诊断类“运行记录”，
  保留“最近会话/新对话”。
- 行程详情：突出“下一项安排”；删除/撤销/导出/回顾收进“更多”；无撤销动作时禁用并说明；
  地图失败给出“地图暂时不可用，行程时间轴仍可正常使用”+ 重试/时间轴/诊断；移动端默认行程 Tab。
- 攻略库：主流程文案改为“攻略库/保存攻略/片段/历史版本/从已保存攻略中选择”，技术信息进诊断。
- 账号注销：`src/api/auth.ts` + `src/api/client.ts`（`skipAuthRedirect`，密码错误 401 不误登出）+
  `Profile` 危险区/密码确认 Modal/成功清状态跳登录。

### 2.3 发布门禁与运维资产

- `scripts/start-e2e-backend.mjs`（新）：三层隔离（cwd 无 `.env.local`、清洗继承环境变量、
  显式关闭外部 provider），H2 内存库，健康检查，日志落 `.e2e/isolated/backend.log`。
- `travel-agent-web/e2e/run-isolated.mjs`（新）+ `package.json` 的 `e2e:isolated` /
  `e2e:device:isolated`；`playwright.config.ts` 在隔离模式先拉起独立后端。
- `travel-agent-web/e2e/isolation.spec.ts`（新）：断言 LLM/AMAP/MCP/路线/价格/时区均为隔离口径。
- `.github/workflows/ci.yml`：e2e/e2e-device 改用隔离后端；新增 `visual` 观察 job。
- `.github/workflows/visual-baseline.yml`（新）：固定 Playwright 容器生成 Linux 视觉基线。
- `docker-compose.yml`：新增 Redis 服务与健康检查；透传 LLM/限流/Embedding/照片/S3/Agent 预算/
  微信/时区等变量；`LLM_MODEL` 默认 `deepseek-flash`；日志轮转；移除退役 `INVOICE_DIR`。
- `travel-agent-web/nginx.conf`：`log_format without_args` 只记 `$uri`，避免 `?ticket=` 进日志。
- 文档：`docs/operations/production-config.md`、`backup-restore.md`、
  `github-security-settings.md`、`docs/legal/*`、`LICENSE`、`.env.production.example`、
  `scripts/backup-drill.ps1`、`.github/pull_request_template.md`。

## 3. 验证记录（本机实跑）

| 命令 | 结果 |
| --- | --- |
| `cd travel-agent-server && mvn -B clean verify`（账号注销落地前） | BUILD SUCCESS：610 tests，0 failures，3 skipped；JaCoCo/Checkstyle/SpotBugs 通过 |
| `cd travel-agent-server && mvn -B verify`（账号注销落地后） | BUILD SUCCESS：**617 tests，0 failures，0 errors，3 skipped**；JaCoCo 门槛、Checkstyle 0、SpotBugs 0 |
| `npm run typecheck` | 通过 |
| `npm run api:check` | 通过（paths=55，schemas=107，生成类型无漂移） |
| `npm run lint` | 0 errors / 48 warnings（全部为既有 hooks/ref 类 warning，无新增 error） |
| `npm run format:check` | 全部通过 |
| `npx vitest run` | **53 files / 532 tests passed** |
| `npm run test:coverage` + `node scripts/check-core-coverage.mjs` | 核心目录 lines **81.61%** / branches **77.95%** / functions **71.88%** / statements **80.43%**，全部达标 |
| `npm run build:check` | 全部预算通过（首屏 JS 597,968 / 600,000 B；precache 2.21 / 2.30 MiB） |
| `npm run test:visual` | **11/11 passed**（Windows 基线） |
| `npm run e2e:isolated` | **14/14 passed**，含 `isolation.spec.ts` 隔离自证 |
| `npm run e2e:device:isolated` | **22/22 passed**（Chromium 桌面 + WebKit iPhone 预设 + CDP 弱网 + 多端并发） |
| `node scripts/docs-check.mjs` | 0 断链、0 失效命令；65 个 Markdown / 397 条链接 / 356 条命令；1 条既有 warning |
| 真实 Key 启动探测（本地 18083，H2 内存库） | `llmConfigured=true`、`llmModel=deepseek-flash`、`llmProvider=api.deepseek.com`、`llmModelAvailable=true`、`llmModelCheckPending=false` |
| 隔离后端状态（18082） | `llmConfigured=false`、`llmVerified=false`、`amapConfigured=false`、`geoProvider=osm`、`routeProvider=estimated`、`mcpEnabled=false`、`mcpToolCount=0`、`timezone=Asia/Shanghai` |
| 账号注销 API 冒烟（隔离后端） | 错误密码 401 后仍可访问 `/api/trips`；正确密码 200；随后 `/api/auth/me` 401、重新登录 401；创建过的行程随账号删除 |
| `python -c "yaml.safe_load(open('docker-compose.yml'))"` | 通过（仅 YAML 语法；本机无 Docker，未跑 `docker compose config`） |

## 4. 未完成 / 未验证（不得当作已通过）

### 4.1 发布前必须关闭

1. **真实 MySQL 8.4**：V23 之后重新跑迁移；备份→恢复→校验演练与 RTO/RPO 记录；
   `scripts/backup-drill.ps1` 只在 fake client 下验证了流程。
2. **真实 iOS Safari**：safe-area、软键盘、PWA 安装、固定底栏、地图、离线/恢复同步；
   Playwright WebKit 只是模拟引擎。
3. **GitHub 后台**：Dependency graph、Code scanning、Secret scanning、分支保护；
   `dependency-review` 仍 `continue-on-error`（见 `docs/operations/github-security-settings.md`）。
4. **法务/许可证**：`[运营主体]`、`[隐私联系邮箱]`、生效日期仍是占位；
   LICENSE 当前为专有声明，是否开源是业务决策。
5. **真实外部服务验收**：真实模型重复 20 次规划；真实高德 MCP/10021 限流、跨城同名 POI、
   polyline、GCJ-02；真实 Embedding provider 的 RAG 评估集。
6. **视觉 Linux 基线**：需运行 `visual-baseline.yml` 并提交 `*-chromium-linux.png`；
   `e2e-device` 观察期到 **2026-12-06** 再决定是否转硬门禁。

### 4.2 已知代码/设计残余风险

1. **JWT 无版本/jti**：注销后旧 token 在过期前仍能通过认证过滤器（业务接口因用户不存在返回 401）；
   若同名重新注册，旧 token 可能映射到新账号。发布前需决策：JWT 加 `uid` + token 版本，
   或注册时禁止复用近期注销用户名。
2. **内存 RAG 向量索引**：注销只删数据库行，`InMemoryVectorStore` 进程内仍持有对象直到重启；
   已删用户无法认证，接口不可访问，但严格“内存无残留”需补 `RagService` 刷新。
3. **旧办公/学习表**：`daily_report` 等无实体/接口，不在注销范围；如需彻底清库需 forward-only 脚本。
4. **首屏 JS 余量仅约 2 KB**：后续新增首屏依赖会撞 build budget，不能靠调阈值绕过。
5. **compose/nginx/Redis/S3 未容器实测**：只做了配置层与 YAML 语法核对。
6. **多意图提示依赖 LLM 输出 `alsoRequested`**：规则兜底不产出该字段，无 Key 时不会提示多意图。

## 5. 上线分级判断

| 级别 | 结论 | 还差什么 |
| --- | --- | --- |
| A. 内部演示 | **具备** | 代码与自动化门禁已通过；注意 Maven 串行执行，不要并发共享 `target` |
| B. 受控灰度 | **未具备** | 真实 MySQL 迁移 + 备份恢复演练；生产 `.env` 启动自检；日志/告警值班；真实 iOS 至少一次关键路径（灰度前） |
| C. 公网 v1 | **未具备** | B 的全部 + 真实 iOS Safari、移动端布局验收、Linux 视觉基线、隐私/删除/许可证定稿、安全扫描硬门禁、回滚方案演练 |
| D. 完整产品 | 未开始 | 真实 Embedding 质量、OTA 实时库存价格、高级个性化/照片轨迹 |

## 6. 建议下一步（按阻断性排序）

1. 在真实 MySQL 8.4 上执行 V23 后迁移与备份恢复演练，把结果写进 `backup-restore.md` 的 RTO/RPO 表。
2. 用全新生产 `.env` 起一套隔离环境，逐项核对 `/api/system/status`、告警、限流、配额与日志脱敏。
3. 开启 GitHub Dependency graph/Code scanning，删掉 `dependency-review` 的 `continue-on-error`。
4. 在 Playwright 容器里生成并提交 Linux 视觉基线，观察 `visual` job，再决定 `e2e-device` 转正。
5. 补真实 iPhone Safari 验收记录（设备/iOS/Safari/网络/截图），同步 PR 模板的边界声明。
6. 决策 JWT 注销后 identity 复用问题；补内存 RAG 刷新与旧表清理策略。
7. 用真实 Key 重复跑 20 次规划与高德限流场景，记录 `completed_partial`、schema 自纠与降级文案。
8. P1-H：接真实 Embedding provider 重跑 7 条 RAG 评估集；P2 能力继续后置。