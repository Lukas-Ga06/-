# travel-agent 工程约定

> 本文件是所有会话（leader 与 worker）每轮都会读到的工程约束。**项目事实的权威来源是 `docs/`**，本文件只做入口与硬约束摘要：与代码冲突时以代码为准，并在同一次改动里修文档。

## 1. 项目坐标

- 双端单体仓：`travel-agent-server`（Spring Boot 3.5.16 / Java 17 / Maven 3.9）· `travel-agent-web`（React 18 + TS 5 + Vite 6 / Node 20 / npm）。
- 数据：默认 H2 文件库 `travel-agent-server/data/travel-agent.mv.db`；生产 MySQL 8。**schema 变更唯一通道是 Flyway**，`sql/init.sql` 已弃用。
- 产品线只有旅行域：行前规划 → 行中调整 → 行后回顾。办公 / 学习 / 健康已退役，不要复活。
- 工具链实测：Node 20.17.0 · npm 10.8.2 · Maven 3.9.16。

## 2. 开工必读（按任务类型挑，别全读）

| 任务 | 先读 |
| --- | --- |
| 任何改动 | `docs/guide/code-structure.md`（分层、17 个包职责、「我要加 X 放哪」） |
| 能力与数据口径 | `docs/guide/capability-boundaries.md`（哪些是真数据、哪些是估算） |
| 改表 / 写迁移 | `docs/guide/data-model.md` · `docs/guide/mysql-migration.md` |
| 改后端链路 | `docs/guide/architecture.md` · `docs/DEVELOPMENT.md`（关键约定，逐条都是评审依据） |
| 改前端 | `docs/guide/frontend.md` · `docs/guide/frontend-quality.md` · `docs/DEVELOPMENT.md` §设计系统 |
| 改接口 / 前端类型 | `docs/guide/api-contract.md`（`npm run api:generate` 与快照测试联动） |
| 提交前 | `docs/guide/quality-gates.md`（门禁清单与阈值） |

## 3. 门禁（按改动面选最小证据链）

后端（`cd travel-agent-server`）：

```bash
mvn -B -Dtest=<TestClass> -DfailIfNoTests=false test   # 覆盖改动的子集
mvn -B verify                                          # 合入前：测试 + JaCoCo + Checkstyle + SpotBugs
mvn -B -Dtest=ReleaseAcceptanceTest -DfailIfNoTests=false test   # 触碰 P0 可靠链路时
```

前端（`cd travel-agent-web`）：

```bash
npm run typecheck && npm run lint && npm run format:check
npm test            # 或 test:coverage + node scripts/check-core-coverage.mjs
npm run build       # 或 build:check（含包体预算）
npm run api:check   # 改了 API 或 generated/schema.d.ts 时
npm run e2e:isolated # 需要先 build；隔离后端由仓根 scripts/start-e2e-backend.mjs 拉起
```

契约与文档：

```bash
# 后端契约快照：改完 API 后必须让它与 docs/api/openapi.json 无 diff
cd travel-agent-server && mvn -B -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test
git diff --exit-code -- docs/api/openapi.json
node scripts/docs-check.mjs   # 改任何 docs/ 之后
```

## 4. 硬约束（简报里按编号引用）

- **R1 工具层只读**：`AgentTool.readOnly()` 默认 true，`ToolRegistry.register()` 拒绝写工具；任何会改数据的能力必须表达为 `AgentPlan` 操作项。
- **R2 唯一写入口**：`ExecutorAgent.commit` 是唯一落库入口（`@Transactional`），行程变更与 `OperationHistory` 必须同事务；界面上的手工增删改走 `withHistory(...)`。新增改变行程的入口必须同时写历史。
- **R3 只动 `PENDING`**：自动/行中重规划只能调整 `PENDING` 条目，`IN_PROGRESS/COMPLETED/SKIPPED` 是既成事实。
- **R4 确认门双指纹**：`planRevision`（行程状态）+ `contentHash`（方案内容）叠加校验，缺 `contentHash` 退化为单指纹，**不要把缺失当失败**；claim 语义与 30 分钟 TTL 不许动。
- **R5 Flyway**：通用脚本 `src/main/resources/db/migration/V*.sql`；MySQL 专属 `db/migration-mysql/`，H2 专属 `db/migration-h2/`（同版本号可各存一份）。已发布脚本原则上不改（checksum）；必改时同步 `flyway repair`，**不要删 `flyway_schema_history`**。`V1__baseline.sql` 不得包含 V2+ 的列/表/索引。
- **R6 测试不触网**：不许访问真实网络、真实大模型、生产数据；后端用 `IntegrationTestSupport`（内存 H2 + MockMvc），错误语义（400/401/403/404/409/500）是测试契约。
- **R7 密钥**：只经环境变量或 `travel-agent-server/.env.local` 注入，不入库、不进 commit、不进日志与回报。生产必须 `DEMO_ACCOUNT=false` 并配 `JWT_SECRET`。
- **R8 门禁只收紧**：JaCoCo 阈值（INSTRUCTION ≥0.76 / BRANCH ≥0.57 / LINE ≥0.76）只许上调；`config/checkstyle/suppressions.xml` 与 `config/spotbugs-exclude.xml` 条目**只减不增**，新增豁免需评审理由。
- **R9 本机 MySQL 有真实实例**（`127.0.0.1:3306`，`root/123456`，按 `docs/DEVELOPMENT.md` 记录；已变更以实际为准）：只建 `travel_agent_*` 临时库且用完 DROP，**不得碰 `lab3_*` / `lab4_mall` / `sky_take_out` 等库**。
- **R10 受限沙箱跑不了前端门禁**：`vitest` / `vite build` 需要子进程管道，在 workspace-write 沙箱下会 `EPERM spawn`，需在 unrestricted 会话执行；跑不动就如实报告，不要伪造全绿。
- **R11 迁移与配额同生共死**：`AgentTraceService` 写入走 `REQUIRES_NEW` 且 try/catch 在事务边界之外（不要改方法级 `@Transactional`）；追踪失败只降级，不得把主流程打成失败。

## 5. 提交与分支

- 当前基分支 `feat/p0-reliability`；不直接 push `main`，**合并与发布只由用户明确指令触发**。
- 提交信息风格：`type(scope): 中文描述`（见 `git log`，如 `feat(obs): 运行详情渲染花费 …`）。独立变更拆开提交，修复提交先于其传播。
- 不跳过 hooks（`--no-verify`）、不用 `--force`（改写历史只许 `--force-with-lease`）。提交前先修好再暂存。

## 6. 本机环境注意

- Windows npm 沙箱坑：若 `npm` 报 `Cannot run a document in the middle of a pipeline`，用 `npm.cmd`；本机 npm 10.8.2 的 arborist bug 使安装依赖需 `--legacy-peer-deps`。
- PowerShell 脚本按 PS 5.1 兼容写法（纯 ASCII 或 UTF-8 with BOM），传空密码用 `-` 占位。
- 并行 Maven 会争抢 `target/`，用 `.tools/mvn-lock.ps1` 串行化。
- 部分 `@SpringBootTest` 需要 `-DargLine=-Djdk.attach.allowAttachSelf=true`；Windows 上 `Could not self-attach to current VM` 属本机策略限制，与代码无关。

## 7. 与下述标准区块的差异

本仓**没有 i18n 层**：用户可见文案直接写中文（`travel-agent-web` 无 i18next/locale 目录），不新增 i18n key 抽象。其余标准约定照常适用。

<!-- dsh-leader-agent:conventions:start -->
# 仓库工程约定

本节由 dsh-leader-agent 写入，作为目标项目的工程约束。它是骨架不是全部——项目特有的规则（架构不变式、目录拓扑、发布流程）在项目自己的文档里定义。

## 沟通与表达

- 注释和日志优先中文，保留专业术语；面向用户的文案遵循项目既有语言约定。
- 报告结论先行，给必要的证据与限制；不写「看起来正确」这类无法核验的判断，缺证据就说缺证据。
- 事实、推断与未验证项分开陈述；未知写未知，不用印象代替核验。
- 完整表达必要的 tradeoff 与风险，不用简洁压掉失败与阻塞。

## 注释纪律

- 默认不写注释，只在 WHY 不明显时补一句。
- 不解释代码做了什么，不引用任务背景或变更历史。
- 注释与被注释代码同生共死；删除代码时一并删除失效注释。
- 对齐文件既有注释密度与措辞风格。

## 代码风格

- 类型安全：不用 `any`，优先 `interface`，类型导入一律用 `import type`。
- 穷尽 `switch` 收口：closed union 的默认分支以 `never` 参数断言收口。
- 禁新增 `as unknown` / `<unknown>` 断言；改用类型收窄或边界校验，存量只减不增。
- `try` 只包可能抛出的那一条语句，避免 `catch` 波及无关失败。
- 平行结构保持对称：无解释的不对称视为漏抽取信号——补解释或合并。
- 标记约定：`FIXME`（阻断发布）、`TODO`（尽快处理）、`XXX`（低优先级优化）。
- 所有工具调用走原生 tool call 形式，不用文本标记模拟协议。

## 失败处理

- 配置错误与未知异常大声报错（Fail Loud），严禁静默吞错。
- 空 `catch` 必须在单行注释中写明吞没原因。
- 输入校验放在系统边界（用户输入、外部 API），内部假设不做重复防御。

## 工程判断

- 新需求先检验前提，再选实施；选解决根因的最小完整改动，不做投机泛化。
- 不为一次性操作创建抽象，不为不可能发生的场景加防御。
- 改动前先搜索既有实现，复用已有模式，最小化变更范围。
- 不做范围外的事：不顺手改无关代码，不新建不必要的文件。
- 结构取舍有多种合理解法时，先写清楚真实用法与所有权归属，再决定形态。
- 本仓未发布时，正确的基础优先于兼容垫片：重命名、改盘上格式在同一次改动内更新全部引用，不留兼容层与迁移路径。

## 状态与数据

- 状态管理遵循宿主与项目既有方案，不另建第二套状态源。
- 单一写入者原则：共享写入优先消除，确实必须共享时明确串行机制。
- 任何送入模型的数据必须能从 session log 重建；新增模型可见输入时同步补对应记录。
- 源码与产物分离：静态检查与测试解析源码，消费构建产物的检查显式声明依赖。

## 用户可见文本

- 用户可见文本走项目的国际化 key，双语文件同步维护，禁止硬编码。
- 新增文案同时补齐所有语言，缺失译文在交付前补上。

## 测试与验证

- 写测试按行为驱动：先固定期望行为，再写实现。
- 本地只跑覆盖改动面的最小证据链，不盲目跑全量套件。
- 严禁真实调用付费模型 API：运行时与工具用录制回放或脚本化替身。
- 不 mock 状态层与组件内部：状态层测纯函数，UI 基于真实 store 渲染。
- 重大缺陷修复必须固化永久回归用例，并在同批改动中记录原因。
- 测试失败就说失败并附输出；没验证就说没验证，不制造全绿假象。

## 提交与交付

- 提交前先修复再暂存最后提交；保持每个提交自洽，不把无关改动混进来。
- 推送前确认变更文件集与影响面，按改动范围选择最小验证证据。
- 独立变更拆开提交；修复引入的提交先于其传播。
- 改写历史一律 `--force-with-lease`，禁止裸 force-push。
- 不跳过 hooks、不绕过签名；hook 失败时排查并修复根因。
- 交付边界是「实现 + 验证 + 提交 + 发起评审」；合并与发布需要用户明确指令。
<!-- dsh-leader-agent:conventions:end -->