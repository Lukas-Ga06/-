# 项目后续工作与上线路线图

> 快照日期：2026-11-22（Asia/Shanghai）  
> 当前分支：`fix/session-isolation-sse-source-key`  1be23f2  
> 适用版本：0.1.0 / Spring Boot 3.5.16 / Java 17 / React 18 / Node 20
>
> **2026-11-25 补记**：P0 可靠执行链路（确认租约/栅栏、事务性 Outbox、运行状态落库、SSE 事件持久化与续传）
> 已在 `feat/p0-reliability` 落地，四阶段映射见 §1.1；§2–§4 的完成度仍以 2026-11-22/23 基线为准，未随本次交付重算。
>
> **2026-11-25 第二阶段补记**：统一花费预算（`AgentSpend`：LLM 调用次数 / 工具次数 / 重试 / token / 估算费用）
> 已在同一分支落地，叠加在既有 deadline 预算上；MCP 逐 server 治理仍待办。证据见
> [花费预算验收记录](review/2026-11-25-spend-budget.md)，状态见 §1.1 第二阶段行。

本文是当前项目的执行口径。它把“代码已经实现”“自动化已经验证”“真实外部服务已经验证”和“可以公网发布”分开，避免把已完成工作重复排期，也避免把本地模拟通过误写成生产就绪。

## 1. 当前结论

项目已经过了原型阶段，核心旅行闭环基本完成：

```text
自然语言需求 → 意图识别 → 规划与研究 → 工具调用 → RAG 引用
→ Diff / 风险审核 → 用户确认 → 行程落库 → 行中调整 → 记账 / 打卡 → 行后回顾 / 撤销
```

当前最准确的判断是：**适合继续做内部演示和受控灰度，不建议在完成 P0 上线阻断项前直接公网正式发布。**

这里的“完成度”按维度看，不用单一代码百分比代替验收：

| 维度 | 当前状态 | 证据 / 说明 |
| --- | --- | --- |
| 旅行核心功能 | 已完成主体 | 登录、规划、确认、行程、地图、记账、备忘、日程、知识库、打卡、回顾、撤销均已落地 |
| Agent 安全边界 | 已完成主体 | 工具只读、确认门、双指纹、风险审核、有限重规划、行中只改 `PENDING` 已有实现 |
| 后端自动化质量 | 已通过本地发布门禁（外部服务用例按设计跳过） | 串行 `mvn -B verify` 通过：测试、JaCoCo、Checkstyle、SpotBugs 全绿，3 个真实 MCP / MySQL 用例按设计跳过；用例数以 CI 输出为准 |
| 前端自动化质量 | 已通过主要门禁 | typecheck、lint、format、coverage、build budget、API 漂移全部通过；用例数与覆盖率以 CI 输出为准，不在本文维护 |
| 浏览器与移动端 | 本地隔离环境已收口，真实 Android 设备仍未验 | 视觉 11/11；隔离后端 E2E 桌面 **14/14**、device（WebKit iPhone 预设 + 弱网 + 多端）**22/22**；`E2E_API_TARGET` 已固化为 `npm run e2e:isolated`。设备模拟仍是 WebKit/iPhone 预设，不等于真实 Android 设备，device job 仍是观察模式 |
| 真实 LLM / 高德 | 模型探测与失败语义已补齐，真实批量验收未做 | `deepseek-flash` 的 `/models` 探测真实通过；工具 schema、研究收尾、MCP 10021 退避、多意图提示已实现；尚未用真实模型重复跑 20 次规划 |
| 生产运维与合规 | 部分完成 | 生产配置清单/模板、账号注销（API+UI）、备份恢复 runbook+脚本、隐私/第三方/删除/许可证文档、日志去票据、compose Redis/S3 透传已落地；真实 MySQL 备份恢复演练、法务确认、GitHub 安全设置仍未完成 |
| UI 视觉一致性 | 已完成主体 | global.css 拆分为 6 个职责文件；Emoji→Ant 图标；首页空足迹、助手入口、行程详情、移动 Tab、攻略库文案已收口；视觉 11/11 |

> **2026-11-22 夜间执行快照**：P0-A/P0-B 代码与门禁改造、E2E 隔离、账号注销、P1-E/P1-G 已完成本地验证；
> 逐项证据与未验证清单见 [P0/P1 执行快照](review/2026-11-22-p0-execution.md)。本文 §3 的“当前完成度”
> 保留为排期基线，具体状态以该快照为准。

### 1.1 四阶段路线图与仓库现状（2026-11-25 校准）

用户提案把工作分为四个阶段。这里不按提案措辞重新描述已完成项，而是逐阶段对照仓库真实状态，
避免在已有实现上重复投入；逐项证据见 [P0 检查清单现实核对](review/2026-11-25-p0-checklist-reality-check.md)
与 [P0 可靠执行链路评审记录](review/2026-11-25-p0-reliability.md)。

| 阶段 | 提案主题 | 仓库真实状态（2026-11-25） | 本次交付 | 排期依据 |
| --- | --- | --- | --- | --- |
| **第一阶段：核心可靠性（P0）** | 持久化状态机、PendingPlan 生命周期与崩溃恢复、Confirm 并发/事务安全、Outbox、SSE 断线恢复 | **5/5 已落地**：`agent_run` 状态/心跳/阶段历史 + `ABANDONED` 恢复；`pending_plan` 租约 + 提交栅栏；`plan_outbox` 与投递器；`agent_run_event` + 帧 id/`Last-Event-ID`/续传端点；前端自动续传一次。后端完整 `mvn verify`、前端全部门禁与隔离 E2E 已在本次通过；真实 MySQL 以 CI 为准 | 本次只交付本阶段 | [现实核对 §2/§4/§5](review/2026-11-25-p0-checklist-reality-check.md)；实现与验证见 [P0 可靠链路评审](review/2026-11-25-p0-reliability.md) |
| **第二阶段：Agent 能力增强** | 多维预算、MCP 治理、旅行数据真实性、审核风险规则、局部重规划、Undo 补偿 | **统一花费预算已落地（2026-11-25）**：`AgentSpend` 叠加在 deadline 上（LLM 调用次数 / 工具次数 / 重试 / token / 估算费用），token 取自供应商 `usage`、缺失不估算；触顶走既有降级语义（回复 + `budget_degraded` + DEGRADE 任务 + `travel.agent.spend` 指标），工具触顶是「跳过并降级」而不是把请求打成 FAILED。已有主体：`McpToolBridge` 只读桥接、`ReflectAgent`/`PlanReviewLoop`、行中锁定、`OperationHistoryService` 撤销。待补：MCP 逐 server 白名单/熔断/配额/审计；真实 Embedding 与 POI 质量验收；局部重规划与 Reflect 风险规则按缺口补 | **花费预算已交付；MCP 治理未开始** | [花费预算验收记录](review/2026-11-25-spend-budget.md)；[现实核对 §3/§8 第 6–7 项](review/2026-11-25-p0-checklist-reality-check.md) |
| **第三阶段：工程化** | CI/质量门禁、可观测性、真实 MySQL、真机与合规 | 已落地：`mvn verify` + 命名发布验收、前端门禁、CodeQL/Trivy/gitleaks、告警路由与送达演练（2026-11-25）。待补：OpenTelemetry（当前零依赖）、真实 Android 真机、真实告警渠道/值班人、法务占位、GitHub 安全设置 | **未开始（仍待办）** | [现实核对 §8 第 8 项](review/2026-11-25-p0-checklist-reality-check.md)（OTel，M）；真机与外部输入项见本文 §5 |
| **第四阶段：产品智能化** | 实时库存/价格、长期偏好记忆、个性化 RAG、回顾增强 | 真实旅行数据链路（天气/POI/路线/预算/足迹）已基本真实化；待补：真实 Embedding provider 的质量与成本、跨旅行长期偏好画像、OTA 实时房态/库存需服务商授权。均属 P2，不阻断 v1 | **未开始（仍待办）** | [现实核对 §8 末段](review/2026-11-25-p0-checklist-reality-check.md)：剩余主要是真实 Embedding 与流量成本，不属于 v1 阻断项；P2 边界见本文 §3 |

**结论**：本次交付只完成**第一阶段 P0**；第二、三、四阶段仍为待办，且不应再按「完全没做」排期——
其中多项已有主体实现，要做的是补缺口。排期顺序按现实核对 §8 的真实缺口与依赖关系，不按提案原文的
20 项平均分配。

## 2. 已完成的工作

以下项目不要再次按“未实现”排期；后续只需要做回归、真实环境验收或体验收口。

| 已完成能力 | 已完成内容 | 当前验收状态 |
| --- | --- | --- |
| 产品范围收缩 | 移除办公、学习、健康等非旅行主线入口；旧地址仅保留兼容重定向 | 代码、配置、文档已同步，旧表暂按环境决定是否退役 |
| 旅行闭环 | 行程、行程项状态机、行中锁定、旅行事件、打卡、足迹口径、旅行回顾 API/页面 | H2 自动化与 HTTP 冒烟已验证 |
| Agent 确认与撤销 | Preview、Diff、风险分级、`planRevision` + `contentHash`、事务提交、before 快照撤销 | 后端测试覆盖；真实模型下已跑通基本链路 |
| 研究与证据 | RAG 引用、POI、天气、路线、来源和降级信息进入方案与 SSE | 本地规则链路与真实 MCP/LLM 各有验收证据 |
| 会话可靠性 | 会话作用域、登出隔离、并发轮次、重命名/删除、SSE 业务错误语义 | V22/V23 已落地并有回归用例 |
| 数据迁移 | Flyway V1–V28（本批新增 V25–V28）；H2 全新库迁移；MySQL 8.4 已在 2026-11-23 验证到 V24（23 条，含 V23→V24 增量升级），V25–V28 的 MySQL 全链待在 CI `mysql-migration` 确认 | 所有新迁移必须 forward-only；迁移后必须跑 `mysql-it` 并留下证据库 |
| 前端主要页面 | 首页、行程列表、行程详情、规划器、助手、回顾、攻略库、诊断、个人中心、PWA 离线只读 | 视觉基线和主要 E2E 已通过 |
| 性能基础线 | 地图引擎按需加载，首屏 JS 约 595 KB，包体预算通过 | 继续保持预算，不要通过放宽阈值掩盖增长 |

## 3. 下一阶段工作清单

优先级说明：P0 是公网发布阻断项；P1 是上线前体验和可信度；P2 是可以后置的增强能力。

### P0-A：锁定生产配置与外部依赖

**要完成什么**

- 把生产环境变量做成一份可审计的配置清单，禁止使用开发默认值。
- **已完成**：`LLM_MODEL` 默认值已从失效的 `deepseek-chat` 改为 `deepseek-flash`（`application.yml` 与 `docker-compose.yml` 同口径），并加了启动模型探测；剩余真实批量压测见 P0-B。
- 生产固定 `JWT_SECRET`、`COOKIE_SECURE=true`、`DEMO_ACCOUNT=false`、关闭 H2 console，容器和 JVM 时区固定为 `Asia/Shanghai`。
- 配置 MySQL、Redis、S3 兼容存储、高德 Web 服务 Key、高德 MCP Key；明确每个 Key 的权限、配额和轮换方式。
- `/api/system/status` 真实显示 LLM、AMAP、MCP 的验证状态、provider、最近验证时间和降级原因；补充 MCP 工具数量。

**当前完成度**

配置项和生产启动保护已有；真实 LLM/MCP/高德链路做过一次验收。默认模型已改为 `deepseek-flash`；MCP 状态未完整暴露、配额和轮换流程尚未收口。

**完成标准**

- 用一份全新的生产 `.env` 启动，未配置关键密钥时明确失败或进入有意的降级状态，不静默伪装成真实数据。
- `system/status` 与日志能说明当前使用的模型、地图/MCP provider 和降级原因，但绝不输出密钥。
- 完成 Key 轮换和最小权限检查；启动日志没有 demo 账号、H2 console、非 Secure Cookie 的生产告警。

### P0-B：收口 Agent 真实运行可靠性

**要完成什么**

- 给所有内置工具提供与 MCP 一致的参数 schema/示例，避免模型猜错 `search_attraction` 的参数名。
- 研究循环在已有足够成功观察时主动 `FINAL`；区分“预算耗尽但已有可用结果”“工具失败”“研究未完成”“完全降级”。
- 给同类重复调用增加去重和收尾策略；高德 `10021` QPS 错误补退避重试或短期熔断，REST 与 MCP 口径一致。
- 将部分成功、可重试、不可重试、取消、超时统一为可读的结果状态，并在前端展示。
- 对一条消息包含多个意图时明确提示“已处理 A，B 需要单独确认/继续”，不能静默只做一半。
- 为总预算、阶段预算、工具超时和模型超时建立可观测指标。

**当前完成度**

核心循环、预算和降级框架已实现；真实 FR-03 复跑暴露了上述 3 个工程缺口和多意图 UX 缺口。

**完成标准**

- 使用真实模型重复跑至少 20 次规划：每次都有明确终态，不能出现无结果的“连接中断”。
- 工具调用参数错误可被 schema 或自纠机制捕获；同参数不会无限重复。
- 真实高德限流时，用户看到“路线暂时使用估算/稍后重试”，而不是开发者堆栈或假实时结果。
- 后端单元测试、契约测试、真实外部服务验收全部覆盖这些分支。

### P0-C：把发布门禁变成真正的阻塞门禁

**要完成什么**

- 串行执行并记录 `mvn clean verify`，避免共享 `target` 并发导致的假失败。
- 前端固定执行 typecheck、lint、format、coverage、core coverage、build budget、API contract。
- 保持 MySQL 8.4 Flyway、Docker Compose、Playwright smoke/device/network/multi 的可重复执行。
- E2E 必须使用独立 H2、关闭 LLM/MCP/外部 Key、关闭限流的后端；通过 `E2E_API_TARGET` 指向该实例，不能复用加载了开发者 `.env.local` 的真实服务。
- 在 GitHub Settings 打开 Dependency graph 和 Code scanning，去掉 dependency-review 的 `continue-on-error`。
- `e2e-device` 连续观察稳定后去掉 `continue-on-error`；视觉回归增加 Linux 基线或固定容器环境后接入 CI。
- 处理 lint 的 47 条 warning，至少把会影响真实行为的 effect dependency、ref render 写法和动态导入告警列入技术债清单。

**当前完成度**

后端和前端主要门禁已能本地通过；远端 CI 已有 7/8 job 通过，剩余仓库设置项未打开；设备套件和视觉回归仍未成为阻塞门禁。最新一次默认本地设备套件因真实 LLM 环境污染为 19/21，隔离无 LLM 后规划器 2/2 通过，说明还需要把“确定性后端启动”固化到脚本或 CI。

**完成标准**

- 干净 runner 上所有必须 job 绿色；安全扫描和 CodeQL 有结果。
- 同一 commit 在干净环境可重复通过，不依赖本机字体、时区、残留数据库或并发 target。
- 规划器 E2E 在启用/未启用真实 provider 的开发环境中结果一致；真实 provider 验收另用独立套件，不与规则引擎门禁混跑。
- PR 模板明确“WebKit 模拟不等于真机”。

### P0-D：数据、安全和运营闭环

**要完成什么**

- 用真实生产类 MySQL 做一次备份、恢复、迁移回滚演练；记录恢复时间目标（RTO）和可接受数据丢失量（RPO）。
- 确认旧业务表（`daily_report`、`mail_item` 等）保留、归档或删除的环境策略，新增 forward-only migration。
- 检查日志脱敏、JWT/Cookie、访问控制、限流、LLM 配额、照片 URL 凭证和对象存储权限。
- 配置健康检查、Prometheus/Grafana、错误告警、外部 provider 失败率和延迟 SLO。
- 补齐隐私政策、数据保留/删除流程、用户导出、第三方服务说明和 LICENSE；README 当前明确“未声明开源许可证”。

**当前完成度**

迁移、指标骨架和安全启动保护已有；备份恢复演练、合规文案和许可证未完成。

**完成标准**

- 值班人员按 runbook 能发现、定位、降级和恢复故障。
- 用户可以删除自己的旅行、照片、会话和相关派生数据，且不会残留可访问对象。
- 发布审批有安全、隐私、备份、依赖扫描四个签字点。

### P1-E：前端运行反馈与错误语义

**要完成什么**

- 将 Agent Activity、工具调用、研究阶段、重规划、确认、同步台账统一成用户可读时间线。
- `ExecutionResultPanel` 明确显示行程、日程、预算、足迹各模块的成功/失败/可重试状态。
- 统一 `loading / empty / degraded / partial / failed / cancelled / complete` 组件和文案。
- 地图 marker、路线段、时间线条目双向联动；显示 `amap` / `estimated` 来源。

**当前完成度**

运行追踪和结果组件已有；不同页面的状态文案和部分成功展示还不统一。

**完成标准**

- 用户不需要看日志就能知道“发生了什么、哪些已经保存、哪些可以重试”。
- 任意失败都有下一步操作：重试、查看时间线、返回修改或打开诊断。

### P1-F：移动端和真实 Android 设备

> 口径（2026-11-23）：v1 真机门禁为真实 Android + Chrome，另加一台国产 ROM 或微信内置浏览器；验收矩阵与记录模板见[上线执行清单 §5.1](operations/launch-checklist.md)。测试代码未改，设备模拟仍是 WebKit/iPhone 预设。

**要完成什么**

- 使用真实 Android Chrome 验证 safe-area、软键盘、系统返回键、滚动、PWA 安装、底部导航和固定 CTA；另用一台国产 ROM（MIUI / ColorOS / 鸿蒙）或微信内置浏览器复验登录与规划。
- 验收 360×800、393×873、412×915、768×1024 横竖屏；检查无横向滚动、长标题最多两行、抽屉和输入框不被系统条遮挡。
- 真实设备覆盖登录、微信内打开、规划、确认、行程操作、地图失败、导出、离线只读和恢复同步。

**当前完成度**

Playwright WebKit/iPhone 预设、弱网、离线和多端并发已通过本地测试；Windows 环境没有真实 Android 真机通道，模拟套件也仍是 iPhone 预设，不能替代真机结论。

**完成标准**

- 至少两台真实 Android 设备（一台接近原生 Android 11+ 跑 Chrome，一台国产 ROM 或微信内置浏览器）通过执行清单 §5.1 的验收矩阵关键路径；测试记录设备型号、Android 版本、Chrome/WebView 版本、ROM、网络条件和截图。
- `e2e-device` 在 CI 转为硬门禁，或明确记录暂不转正的风险和期限。

### P1-G：视觉系统和高频页面收口

详细方案见本文第 4 节。优先做首页、我的行程、行程详情、AI 助手，再处理攻略库和诊断页。

### P1-H：真实检索质量验收

- 用真实 Embedding provider 重跑现有 7 条 RAG 评估集，记录 Recall@5、MRR、拒答率和成本。
- 真实高德 Key 下验证跨城同名 POI、路线 polyline、GCJ-02/WGS-84 转换、限流和降级。
- 结果必须标出来源、获取时间、过期时间和是否降级；估算价格不得写成实时库存。

**当前完成度**：RAG 目前只有本地哈希 Embedding 基线（Recall@5=0.857、MRR=0.714），真实质量未验；MCP 工具已逐项真实调用，但上游参数缺陷仍需修复。

### P2：可以后置的增强能力

- OTA 实时房态、库存和价格：需要服务商授权，不能用参考价冒充实时库存。
- 照片/轨迹的高级整理、自动路线回放和更丰富的回顾可视化。
- 更高质量的个性化 RAG、长期偏好记忆和跨旅行推荐。

这些不是 v1 公网发布的硬阻断项，但必须在产品页面明确“参考数据/降级数据”口径。

## 4. UI 改造方案

### 4.1 视觉方向

目标不是再增加装饰，而是做成一个适合反复使用的“旅行工作台”：信息密度适中、主动作清楚、状态可扫描、地图和 AI 服务于行程，不抢主任务。

- 背景使用中性浅灰，内容区域白色；避免大面积玻璃、渐变和装饰性光晕。
- 一个页面只保留一个主动作，删除、撤销、导出、回顾放入“更多”。
- 绿色只表示成功，橙色表示待核实/降级，红色表示危险；状态同时显示文字或图标，不能只靠颜色。
- 页面区域用分隔线和留白组织，不要“卡片套卡片”；卡片圆角不超过 8px，弹窗/抽屉不超过 10px。

### 4.2 统一设计令牌

先在 `src/styles/tokens.css` 建令牌，再让各页面使用变量；不要继续在 `global.css` 文件尾部追加覆盖规则。

```css
:root {
  --bg: #f6f7f9;
  --surface: #ffffff;
  --surface-2: #f9fafb;
  --border: #e4e6eb;
  --text: #111827;
  --text-2: #4b5563;
  --text-3: #6b7280;
  --accent: #1f6feb;
  --accent-hover: #185abd;
  --success: #16803c;
  --warning: #b45309;
  --danger: #c62828;
  --focus: #1f6feb;
  --radius-control: 6px;
  --radius-card: 8px;
  --radius-dialog: 10px;
  --shadow-float: 0 8px 24px rgba(17, 24, 39, 0.12);
}
```

同时统一：按钮高度 36px、紧凑按钮 32px、输入框高度 40px、页面间距 16/24px、正文行高 1.5、标题最多两行。焦点环使用 2px 高对比色，不用只改变阴影。

### 4.3 全局和导航

| 问题 | 具体改法 | 文件落点 |
| --- | --- | --- |
| Emoji 与 Ant 图标混用 | 导航、标题、地图控制器、Tab 全部改用 `@ant-design/icons`；Emoji 只保留在用户内容/聊天正文 | `src/components/AppShell.tsx`、`src/pages/*`、`src/components/MapView`、`FootprintMap` |
| 图标按钮不可理解 | 所有纯图标按钮增加 `Tooltip`、`aria-label` 和稳定尺寸 | AppShell、地图、聊天、更多菜单 |
| 旧新 CSS 并存 | 将 `global.css` 拆成 `tokens.css`、`shell.css`、`buttons.css`、`cards.css`、`status.css` 和页面 CSS，删除重复选择器 | `src/styles/global.css`、`src/styles/*` |
| 移动底栏遮挡 | 底栏使用 `padding-bottom: env(safe-area-inset-bottom)`，正文和输入区额外留 12–16px | AppShell、ChatPanel、页面布局 CSS |

### 4.4 首页

当前首页足迹地图在无数据或地图失败时占据大面积，截图会像“空白地图”。改成以下结构：

1. 首屏：问候 + AI 输入框 + 4 个常用入口（规划旅行、调整天气、记一笔消费、查询攻略）。
2. 下一次旅行：标题、日期、预算、一个主按钮“查看行程”。
3. 今天要做什么 / 待处理：两列桌面布局，移动端纵向排列。
4. 足迹：只有 `visitedPlaces.length > 0` 才渲染地图；没有足迹时显示 80–96px 紧凑空状态和一个主按钮。

具体改法：

- `Home/index.tsx`：按 `visitedPlaces` 条件渲染地图，区分“无数据”和“地图加载失败”。
- `home.css`：降低地图容器最小高度，避免空状态撑到 300–380px。
- 空状态文案用“还没有已打卡的足迹”，不要把 Key、网络和环境变量暴露给普通用户。

### 4.5 我的行程与行程详情

- 我的行程页保持现有搜索、筛选、分页；移动端把状态、预算、操作拆成上下两层，删除放进更多菜单。
- 行程详情页顶部只保留预算/记账和更多；把“下一项安排”作为首要视觉焦点。
- 桌面保持时间轴 2/3、地图/助手 1/3；移动端默认“行程”，地图和 AI 作为 Tab，不在首屏抢高度。
- 时间线状态用“未开始 / 进行中 / 已完成 / 已跳过”文字 + 图标，路线段显示来源标签。
- 撤销菜单没有可撤销动作时隐藏或禁用，并说明“当前没有可撤销操作”。
- 地图失败改成“地图暂时不可用，行程时间轴仍可正常使用”，提供“重试地图 / 查看时间轴 / 打开诊断”。

文件：`src/pages/Travel/index.tsx`、`src/pages/Trip/index.tsx`、`src/components/MapView/index.tsx` 及对应 CSS。

### 4.6 AI 助手

当前空态聊天面板可用但垂直空白过多。改法：

- 将内联 `minHeight` 移到页面级 CSS；桌面空态居中但限制最大高度。
- 空态只放 3–4 个明确入口，按钮文案直接表达任务，不用长说明。
- 输入区采用 sticky/fixed 底部布局，补 safe-area；输入区与底栏之间至少留 12–16px。
- 正在生成显示阶段、停止按钮、部分成功和重试原因；不要只显示“连接中断”。

文件：`src/pages/Assistant/index.tsx`、`src/components/ChatPanel/index.tsx`、`assistant.css`。

### 4.7 攻略库与诊断

将实现术语从普通用户主流程移到详情/诊断：

| 当前文案 | 建议文案 |
| --- | --- |
| 旅行知识库 | 攻略库 |
| 添加文档 / 入库并切块 | 保存攻略 |
| 文档版本与回滚 | 历史版本 |
| 文档 ID | 从已保存攻略中选择 |
| 切块 | 片段 |
| 指纹 / 向量索引已重建 | 放到详情或诊断；主流程显示“攻略已更新” |

文件：`src/pages/Knowledge/index.tsx`、`knowledge.css`。诊断页可以保留 provider、Key、MCP 工具数量等技术信息，但不要把这些错误直接展示在普通行程页。

### 4.8 UI 验收标准

- 320、390、768、1024、1440px 无横向滚动。
- 长标题最多两行；操作区不与标题争夺同一行。
- 空、加载、失败、离线、部分成功、待确认有不同图标、文字和操作。
- WCAG AA 普通文字对比度；键盘可以完成登录、规划、确认、状态操作和撤销。
- 所有 icon-only 控件有名称和 Tooltip；地图错误不暴露技术配置。
- 视觉回归在固定环境可重复；真实 Android 设备至少完成一次关键流程验收。

## 5. 分级上线定义

### A. 内部演示

满足：H2、规则引擎降级、前端主要门禁通过；使用 demo 账号和本地地图/模型替身。  
当前基本满足，但共享 `target` 的构建必须串行执行。

### B. 受控灰度 / 邀请测试

除 A 外，还必须满足：P0-A 的真实配置、P0-B 的失败语义和限流保护、MySQL 迁移、备份恢复一次演练、核心用户路径 E2E、日志和告警可用。  
当前未完全满足，主要缺口是默认模型、真实 Agent 边界和运营闭环。

### C. 公网 v1

必须全部满足：

- P0-A～P0-D 全部完成并有发布记录。
- 后端 `mvn clean verify`、前端全部门禁、MySQL/Docker、smoke/device/network/multi、契约和安全扫描硬绿。
- 真实 Android 设备关键路径通过；地图/LLM/MCP 失败时用户仍能完成核心行程操作。
- 隐私、删除、备份恢复、许可证和第三方服务说明已发布。
- 至少一次灰度观察窗口无 P0/P1 故障，且有回滚方案。

### D. 完整产品

在 C 基础上再完成真实 Embedding 质量调优、OTA 实时库存/价格授权、个性化 RAG 和高级照片/轨迹能力。它不是 v1 公网发布的前置条件。

## 6. 发布前命令清单

```bash
# 后端（必须无其他 Maven 进程）
cd travel-agent-server
mvn -B clean verify

# 前端
cd ../travel-agent-web
npm ci
npm run typecheck
npm run lint
npm run format:check
npm run test:coverage
node scripts/check-core-coverage.mjs
npm run build:check
npm run api:check

# 浏览器与视觉（隔离后端：独立 H2 + LLM/MCP/AMAP 全关，不读开发者 .env.local）
npm run e2e:isolated
npm run e2e:device:isolated
npm run test:visual

# 需要复用已启动的隔离后端时（调试用；发布记录用上面的 e2e:isolated）：
# E2E_API_TARGET=http://127.0.0.1:18081 E2E_BASE_URL=http://localhost:4173 npx playwright test

# 文档与真实迁移
cd ..
node scripts/docs-check.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mysql-it.ps1
```

发布记录必须附上：commit、环境变量版本、迁移版本、门禁链接、真实 provider 验收结果、备份恢复时间、已知风险和回滚方式。

## 7. 建议执行顺序

1. 先完成 P0-A：锁定生产模型、Key、时区、安全开关和 status 诊断。
2. 同时完成 P0-B：工具 schema、研究循环收尾、QPS 重试、部分成功语义和多意图提示。
3. 完成 P0-C/P0-D：远端安全设置、硬门禁、备份恢复、监控、隐私与许可证。
4. 做 P1-E/P1-F：统一运行反馈并完成真实 Android 真机验收。
5. 按第 4 节顺序做 UI 收口，先首页、行程详情、助手，再攻略库和诊断。
6. 以灰度数据决定是否进入公网 v1；OTA 和高级个性化能力放到 P2。

