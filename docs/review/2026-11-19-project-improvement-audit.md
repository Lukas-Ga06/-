# 项目改进审计（2026-11-19）

> 快照日期：2026-11-19 ｜ 验证范围：当前工作区源码、CI 配置、本地构建结果 ｜ 结论：优先发布可信 / 性能 / 契约 / 可观测，而非继续横向加功能（详见 §1）

本文基于当前工作区源码、CI 配置和本地构建结果，回答两个问题：项目下一步最值得改什么，以及如何判断改完了。它是改进清单，不替代产品路线图、架构文档或历史验收报告。

## 1. 结论

当前项目已经具备可运行的旅行闭环、较完整的自动化测试和覆盖开发/部署/API/数据模型的文档套件。下一阶段不应继续横向扩充功能，优先级应从“功能可用”转向“发布可信、性能可控、契约可维护、线上可观测”。

建议按以下顺序推进：

1. **P0：建立可重复的发布门禁**，补真实 MySQL 迁移验证、安全扫描、覆盖率基线和前后端静态检查。
2. **P0：处理前端地图包体**，当前生产构建的 `maps` chunk 为 1,060.92 kB，已超过 800 kB 告警阈值。
3. **P1：消除后端启动与依赖告警**，明确 JPA/Redis repository 扫描边界，并替换已迁移的 PDF 渲染依赖坐标。
4. **P1：把 API 契约从手工文档升级为可校验契约**，避免 Controller、TypeScript 类型和 `api-reference.md` 三处漂移。
5. **P1：建立真实服务降级验收**，覆盖高德、LLM、Embedding、对象存储、Redis 的超时、限流与不可用场景。
6. **P2：治理文档生命周期**，把当前事实、历史证据和未来计划分层，减少静态数量与过期说明。

## 2. 本次验证基线

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 后端单元/集成测试 | 通过 | `mvn test`：461 tests，0 failures，0 errors，0 skipped |
| 前端单元测试 | 通过 | `npm test -- --run`：30 个文件、216 tests 全部通过 |
| 前端生产构建 | 通过但有告警 | `npm run build` 成功；地图 chunk 1,060.92 kB，gzip 287.02 kB |
| CI 主链路 | 已具备 | Maven verify、前端 typecheck/test/build、Playwright smoke |
| 当前源码规模 | 已核对 | 后端 222 个 Java 主代码文件、12 个 Controller、19 个实体、9 个工具实现；前端 117 个 TS/TSX/CSS 源文件 |

本次没有重跑 Docker Compose、真实 MySQL、真实高德/LLM/Embedding、S3/Redis 多实例和浏览器视觉回归；这些属于下述待补门禁，而不是本次“已通过”的范围。

## 3. 优先改进项

### P0-1 发布门禁仍缺关键层

**现状证据**：CI 已执行 H2 后端测试、前端测试/构建和 Playwright smoke，但没有真实 MySQL migration job、覆盖率阈值、Java/TypeScript lint、依赖漏洞或密钥扫描。

**风险**：H2 兼容模式不能完全代表 MySQL；测试数量增加不等于关键分支被覆盖；风格、潜在缺陷和已知漏洞只能依赖人工发现。

**建议实施**：

- 在 CI 增加 MySQL service container，至少执行 Flyway 全新建库、`validate` 和一组核心仓储测试；升级发布再加“上一正式版本快照 -> 当前迁移”。
- 后端引入 JaCoCo，先记录基线，再对核心包设置逐步提高的门槛；前端引入 Vitest coverage，优先约束 `store/api/lib` 与闭环组件。
- 后端增加 SpotBugs 或 Error Prone、Checkstyle；前端增加 ESLint（含 React Hooks）与 Prettier check。
- 增加 Dependabot/Renovate、CodeQL（或等价 SAST）与 secret scanning；扫描失败必须阻断合并。

**完成标准**：PR 必须同时通过 H2、MySQL migration、静态检查、覆盖率和安全扫描；门禁及本地复现命令写入开发手册。

### P0-2 地图首载包体过大

**现状证据**：生产构建成功，但 `maps-*.js` 为 1,060.92 kB（gzip 287.02 kB），Vite 提示 chunk 超过 800 kB。ECharts 主 chunk 也为 550.48 kB。

**风险**：移动网络下首屏交互变慢，低端设备解析/执行成本较高；PWA 更新也需要下载较大的 precache（本次共约 2.98 MiB）。

**建议实施**：

- 地图和图表只在进入相应页面或组件进入视口后动态加载，避免首页/非地图页面预取完整引擎。
- 检查 MapLibre、高德 Provider 与 ECharts 是否被重复打包；ECharts 保持按图表/组件注册，不引入全量入口。
- 对 `manualChunks` 结果做 bundle analyzer 分析，以真实模块占比决定拆分，不以提高告警阈值作为修复。
- 在 CI 记录关键 chunk 与总 precache 体积，设置允许小幅波动的预算。

**完成标准**：地图 chunk 低于 800 kB，非地图页面不下载地图运行时；移动端冷启动指标建立基线，后续 PR 的包体回归可被 CI 检出。

### P1-1 后端日志与依赖告警需要清理

**现状证据**：测试启动时 Spring Data Redis 会逐个尝试识别 19 个 JPA Repository，产生大量 “Could not safely identify store assignment” 日志；Maven 提示 `flying-saucer-pdf-openpdf:9.4.0` 已迁移到新 artifact；测试日志中的部分中文在当前控制台出现乱码。

**风险**：告警噪声掩盖真正故障，升级路径不清晰，Windows/CI 的故障日志可读性不一致。

**建议实施**：明确 JPA 与 Redis repository 扫描范围；升级到官方当前 artifact 并做路书 PDF 回归；统一 Maven Surefire、JVM、日志与 PowerShell 的 UTF-8 编码。

**完成标准**：正常启动和测试不再出现 repository 误判、artifact relocated 或中文乱码；PDF 导出测试继续通过。

### P1-2 API 契约仍依赖人工同步

**现状证据**：接口由 Controller、DTO、前端 `api/types` 和 `docs/guide/api-reference.md` 分别维护，当前没有 OpenAPI 生成或契约差异检查。

**风险**：字段、错误码、可空性和枚举新增时容易只改一端；文档很完整，但完整的手工文档反而提高维护成本。

**建议实施**：以 OpenAPI 作为机器可校验契约，保留人工文档解释业务语义；从 schema 生成或校验 TypeScript 类型；CI 对 schema diff 做 breaking-change 检查。

**完成标准**：新增/修改接口必须先更新契约；后端测试校验响应，前端类型由契约生成或自动比对，API 参考不再手写重复字段表。

### P1-3 外部依赖的降级路径需要系统化验证

**现状证据**：项目已经为高德、LLM、Embedding、Redis、S3 提供开关或降级路径，但 CI 主链路主要运行本地/H2/模拟模式。

**风险**：真实服务的超时、429、鉴权失败、半成功和恢复过程最容易在生产暴露；“有 fallback”不代表 fallback 在所有调用链上都能守住 SLA 与数据一致性。

**建议实施**：使用 WireMock/Testcontainers 模拟超时、429、5xx、坏 JSON 和连接恢复；为 preview/confirm 幂等、SSE 断连、对象存储失败和 Redis 不可用建立故障矩阵；真实服务只做定时或手工 smoke，避免 PR 依赖外部稳定性。

**完成标准**：每个外部依赖都有超时预算、重试策略、降级结果、用户提示和监控指标；故障矩阵有自动化测试对应。

### P1-4 可观测性需要从“能看”提升到“可运营”

**现状证据**：已有 Actuator、trace id、Agent run/task/tool trace 和部分指标，但文档尚未给出统一 SLO、告警阈值与 dashboard 责任边界。

**建议实施**：定义规划成功率、确认提交成功率、SSE 中断率、外部服务降级率、P95 延迟、RAG 命中/拒绝率、LLM 成本和任务积压等核心指标；补 Prometheus/Grafana 示例和最小告警集；日志字段统一 `traceId/userIdHash/runId/tripId/provider/degraded`。

**完成标准**：线上问题能从用户请求追到 Agent run、工具调用与提交事务；每个 P0 指标有 SLO、告警阈值和处置链接。

### P2-1 文档需要生命周期治理

**现状证据**：文档已覆盖产品、开发、架构、API、数据模型、前端、部署和多份验收报告，但 README 也承载了大量实现细节、静态文件数量和历史批次描述。

**风险**：同一事实多处重复，更新时容易漏改；“已验收于某日”容易被读成“当前版本仍保证”；静态计数维护价值低、漂移概率高。

**建议实施**：

- README 只保留定位、5 分钟启动、核心闭环和文档入口；实现细节放入 guide。
- `guide/` 表达当前事实，`review/` 保存带日期的验证快照，`proposals/` 只放未落地方案；过期 proposal 标注状态或归档。
- 每篇核心文档增加 `状态/适用版本/最后验证日期/事实来源` 元数据。
- 静态数量只保留确有决策价值的指标，其他通过脚本生成；CI 增加 Markdown link check 与关键命令 smoke。

**完成标准**：一个事实只有一个权威位置；历史证据带日期且不会冒充当前状态；链接和可执行命令在 CI 中自动校验。

## 4. 建议迭代顺序

| 迭代 | 目标 | 建议交付物 |
| --- | --- | --- |
| 第 1 周 | 发布门禁 + 告警降噪 | MySQL CI、lint、覆盖率基线、依赖/密钥扫描、repository 扫描边界、依赖迁移 |
| 第 2 周 | 前端性能 + 契约 | 地图按需加载、包体预算、OpenAPI schema、TS 契约校验 |
| 第 3 周 | 韧性 + 可观测性 | 外部依赖故障矩阵、核心 SLO、dashboard/alert/runbook |
| 第 4 周 | 文档收敛 | README 精简、文档元数据、链接检查、proposal 状态治理 |

每一周都应保持当前核心闭环回归：登录 -> 规划 -> Diff -> 确认 -> 行程状态推进 -> 回顾 -> 撤销。新增门禁应先记录基线，再设置可实现的阈值，避免一次性引入大量与产品风险无关的整改。

## 5. 暂不建议优先做的事

- 暂不继续增加办公、学习、健康等横向场景，产品边界已经明确收敛到旅行。
- 暂不为消除构建告警简单调高 chunk 阈值，应先分析并减少非必要首载代码。
- 暂不把真实第三方服务放入每个 PR 的强依赖链路，PR 应使用可控模拟，真实 smoke 独立运行。
- 暂不追求全仓统一的高覆盖率数字，优先覆盖确认提交、撤销、权限隔离、迁移、故障降级等高风险路径。

