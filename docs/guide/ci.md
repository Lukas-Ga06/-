# CI 门禁：每个 job 做什么、本地怎么复现、失败怎么处理

> 状态：当前事实 | 适用版本：0.1.0（GitHub Actions / JDK 17 / Node 20） | 最后验证：2026-11-25 | 事实来源：`.github/workflows/ci.yml`、`.github/workflows/codeql.yml`、`.github/workflows/visual-baseline.yml`、`.github/dependabot.yml`、`.gitleaks.toml`、`lychee.toml`、`travel-agent-web/package.json`、`travel-agent-server/pom.xml`

## 1. job 总览

| job | 触发 | 关键门禁 | 本地等价 |
| --- | --- | --- | --- |
| `backend` | push/PR | `mvn verify`：测试 + JaCoCo + Checkstyle + SpotBugs | `cd travel-agent-server && mvn -B verify` |
| `mysql-migration` | push/PR | 真实 MySQL 8.4 上 Flyway 全量迁移 + Hibernate validate | `powershell -File scripts/mysql-it.ps1`（见 [mysql-migration.md](mysql-migration.md)） |
| `frontend` | push/PR | typecheck / lint / format / 覆盖率 / 包体预算 / 契约类型漂移 | `cd travel-agent-web && npm run typecheck && npm run lint && npm run format:check && npm run test:coverage && node scripts/check-core-coverage.mjs && npm run build:check && npm run api:check` |
| `contract` | push/PR | OpenAPI 快照无漂移；PR 上 oasdiff 破坏性变更 | 见第 4 节 |
| `docs` | push/PR | 相对链接存在、文档命令真实可用、lychee 本地链接 | `node scripts/docs-check.mjs` |
| `security` | push/PR | 依赖漏洞（dependency-review【硬门禁】/ npm audit / Trivy） | 见第 5 节 |
| `secret-scan` | push/PR | gitleaks 全历史密钥扫描（固定 8.30.1 + SHA-256，命中即失败） | 见第 5 节 |
| `e2e` | push/PR | Playwright **14 条**（Chromium 桌面）：登录/建行程/路书/PWA 资源、地图引擎运行时加载、**5 个页面的 axe 无障碍扫描（critical/serious 必须为 0）**、**1 条隔离自证**；后端是独立 H2 内存库 + LLM/MCP/AMAP 全关 | `cd travel-agent-web && npm run e2e:isolated` |
| `e2e-device` | push/PR | **观察中（`continue-on-error: true`，不阻塞合并）**：移动端 WebKit/iPhone 预设 3 条 + CDP 弱网/离线 3 条 + 多端并发 2 条（连同 13 条主冒烟，共 21 条），同样跑隔离后端 | `cd travel-agent-web && npm run e2e:device:isolated` |
| `visual` | push/PR | 11 张截图逐像素比较（`maxDiffPixelRatio: 0`）；**仅有 Linux 基线时比较**，当前 `continue-on-error: true` 观察 | `cd travel-agent-web && npm run test:visual`（需 Linux 基线，见第 3 节 visual） |
| `CodeQL` | push/PR/每周 | Java + TypeScript SAST | 本地无法等价，靠 PR 结果（第 6 节） |

### 1.1 e2e 后端为什么必须隔离

2026-11-22 的设备套件在默认 `.env.local` 环境上出现 19/21：两条规划器用例被真实 LLM 卡住。
根因不是用例，而是**启动方式**：`application.yml` 里的
`spring.config.import: optional:file:./.env.local` 按进程工作目录解析，从
`travel-agent-server/` 启动的 e2e 后端会把真实 LLM/高德 Key 读进来。

现在 `npm run e2e:isolated` 由 Playwright 的 webServer 拉起
[`scripts/start-e2e-backend.mjs`](../../scripts/start-e2e-backend.mjs)，做三层隔离：

1. **工作目录**：java 进程 cwd 是 `.e2e/isolated/`，这里没有 `.env.local`，可选 import 落空；
2. **环境清洗**：删除继承的 `SPRING_*`、`LLM_API_KEY`、`AMAP_*`、`JWT_SECRET`、`MYSQL_*` 等变量；
3. **显式关闭**：LLM/Embedding/MCP/AMAP/Redis 与限流全部关闭，H2 用内存库，每次启动全新。

`e2e/isolation.spec.ts` 会请求 `/api/system/status` 并断言
`llmConfigured=false`、`amapConfigured=false`、`mcpToolCount=0`、`routeProvider=estimated`、
`timezone=Asia/Shanghai`。配置一旦漂移，门禁变红，而不是静默通过。

> 普通 `npx playwright test`（不带 `E2E_START_BACKEND=1`）仍会连 8080，方便本地快速迭代；
> 它**不是**发布证据，日志会打印一次「未启用隔离后端」的警告。

### 1.2 哪些套件仍未转正，为什么

| 套件 | 现状 | 原因 / 接入条件 |
| --- | --- | --- |
| 移动端 WebKit | 已进 CI，**观察模式** | 隔离后端已解决外部污染；剩余风险是共享 runner 上 WebKit 与耗时阈值的抖动。转正条件：连续 2 周无「只有 CI 红、本地绿」，且 PR 模板写明「WebKit 模拟 ≠ iOS 真机」 |
| 弱网 / 离线 | 已进 CI，**观察模式** | 依赖 `Network.emulateNetworkConditions`（仅 Chromium）；断言是耗时阈值（用例按 ≤20s，实测 8.3s，约 2.4 倍余量），共享 runner 上需观察波动 |
| 多端并发 | 已进 CI，**观察模式** | 两个独立 `browserContext` 同账号并发；依赖后端关限流（隔离后端已关） |
| 视觉回归 | 已接 CI，**观察模式** | 2026-11-24 起仓库有两套基线：`-chromium-win32.png`（本机人工回归）与 `-chromium-linux.png`（`.github/workflows/visual-baseline.yml` 在固定 Playwright 容器生成，本次 run 45381999718）；`visual` job 检测到 Linux 基线后已由「跳过」转为逐像素比较，稳定后再摘 `continue-on-error` |

`e2e-device` 转正（去掉 `continue-on-error`）的复盘期限：**2026-12-06**。

### 1.3 CI 自动门禁 vs 人工验收

CI 只证明「不联网、无真实 Key 时也能正确失败并自报」。**它不能证明真实外部依赖可用**，
下面这些项必须在有 Key/有网络的环境里人工验收，CI 绿了不代表它们通过：

| 验收项 | CI 能否自动覆盖 | 为什么 | 人工验收方式 |
| --- | --- | --- | --- |
| 规则规划器闭环、确认/撤销、预算与足迹口径 | ✅ 自动 | 确定性逻辑，隔离后端可跑 | — |
| 天气不可用时的拒绝语义（503、不生成方案） | ✅ 自动 | 单测直接构造「不可用 + 原因」，不依赖网络 | `WeatherDependentModificationTest` |
| **天气真实预报可用** | ❌ 人工 | e2e 用 `WEATHER_SIMULATION=true` 隔离外呼，`WeatherServiceTest` 也只覆盖 WireMock 解析——都**不证明能连上真实 Open-Meteo** | 部署后看 `/api/system/status` 的 `weatherProvider=open-meteo` 与 `weatherHint` |
| **LLM 真实可用** | ❌ 人工 | CI 不持有 `LLM_API_KEY`，只验回落规则规划器 | 配 Key 后跑 [真实模型验收](v2.2-iteration.md) 流程并留存证据 |
| **高德 POI / 路线真实可用** | ❌ 人工 | 需要真实 `AMAP_KEY` 且有 QPS 配额 | `.e2e/amap-real-key-probe.ps1`，结果写进 `docs/review/` |
| 移动端真机（Android） | ❌ 人工 | CI 只有 WebKit/iPhone 预设，模拟 ≠ 真机 | 按 [发布检查单](../operations/launch-checklist.md) §5.1 跑真机矩阵 |
| 视觉回归基线 | ✅ 自动比较（Linux 基线） | Linux 基线已入库（11 张，Playwright `v1.64.1-jammy` 生成），CI 用同一镜像逐像素比较；重新生成仍需人工触发 | 有意改视觉时跑 `.github/workflows/visual-baseline.yml` 并人工 review 图片 diff |
| 生产部署配置（密钥、`DEMO_ACCOUNT=false`、`WEATHER_SIMULATION=false`） | ❌ 人工 | CI 用的是隔离配置 | 按 [生产配置](../operations/production-config.md) 逐项核对 `/api/system/status` |

判定规则：**CI 失败 = 不能合并**；**上表 ❌ 项未人工验收 = 不能算「能力可用」**，只能算「代码路径存在」。
对外介绍与发布说明必须按 [能力边界与默认口径](capability-boundaries.md) 表述，不得用 CI 结果替代真实依赖验收。

## 2. 本地一次跑完全部快速门禁

```bash
# 后端（约 3.5 分钟：测试 + 覆盖率 + 静态检查）
cd travel-agent-server && mvn -B verify

# 前端（约 2-3 分钟）
cd travel-agent-web
npm ci
npm run typecheck && npm run lint && npm run format:check
npm run test:coverage && node scripts/check-core-coverage.mjs
npm run build:check && npm run api:check

# 文档
cd .. && node scripts/docs-check.mjs

# 浏览器 e2e（隔离后端：独立 H2 + 无真实 LLM/MCP/外部 Key）
cd travel-agent-web
npm run build
npm run e2e:isolated            # Chromium 桌面门禁
npm run e2e:device:isolated     # WebKit 移动端 + 弱网 + 多端并发（观察）

# 真实 MySQL 迁移（需本地实例，见 mysql-migration.md）
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mysql-it.ps1
```

## 3. job 细节与失败处置

### backend
- 失败点与处置见 [quality-gates.md](quality-gates.md)：覆盖率低于阈值 → 补测试；Checkstyle 违规 → 修代码或按 ratchet 流程改 `config/checkstyle/suppressions.xml`；SpotBugs 命中 → 先认定为真缺陷并修，确认误报才加精确 exclude。
- 产物：`backend-reports`（JaCoCo HTML/CSV、SpotBugs XML、Checkstyle XML）。

### mysql-migration
- 用 `mysql:8.4` service container，`MYSQL_IT=true` 开启测试；测试自建一次性库 `travel_agent_it_<ts>`，不删不留依赖。
- 失败排查顺序：service 健康检查是否通过 → 账号是否有 `CREATE DATABASE` → 具体迁移版本/列名（测试断言信息带对象名）→ Flyway 与 MySQL 8.4 的版本警告（属预期，不是失败）。

### frontend
- 覆盖率双阈值：全局在 `vitest.config.ts`，`src/store|api|lib` 在 `scripts/check-core-coverage.mjs`；规则与 ratchet 见 [frontend-quality.md](frontend-quality.md)。
- 包体预算在 `travel-agent-web/scripts/check-bundle-budget.mjs`；方案与数字见 [frontend-performance.md](frontend-performance.md)。**不允许通过调高 `chunkSizeWarningLimit` 让预算失败消失。**
- 契约类型漂移：`docs/api/openapi.json` 变了就必须 `npm run api:generate`。

### e2e（隔离后端）
- 本地首次跑会构建后端 jar（`mvn -Dmaven.test.skip=true package`，约 30–60s），之后按源码 mtime 自动复用；`--force-build` 可强制重建。
- 隔离实例固定 `http://127.0.0.1:18081`（可用 `E2E_API_PORT` 改），日志在 `.e2e/isolated/backend.log`。**端口被占用时脚本会直接拒绝启动**，不会复用可能的开发者实例；先停旧进程再跑。
- 后端 cwd 必须是 `.e2e/isolated/`。如果该目录出现 `.env.local`，脚本拒绝启动——这是防止有人“手工把真实 Key 拷进隔离环境”。
- 失败排查：先看 `.e2e/isolated/backend.log` 的启动异常（Flyway/H2/端口），再看 Playwright 的 `test-results`。若 `isolation.spec.ts` 报 `llmConfigured=true` 或 `mcpToolCount>0`，说明隔离配置被绕过，**不要**改断言，先查启动参数与环境变量。
- 环境变量继承清洗清单在脚本 `isolatedEnv()`；新增外部依赖（如新的 provider Key）时要同步加进删除列表。

### visual（Linux 基线）
- 生成：Actions →「Visual baseline (Linux)」→ Run workflow → 下载 `visual-linux-baselines`，把 `*-chromium-linux.png` 提交到 `travel-agent-web/e2e/visual/pages.spec.ts-snapshots/`。
- 比较：`visual` job 与生成器使用同一个 `mcr.microsoft.com/playwright:v1.64.1-jammy` 镜像，检测到 Linux 基线才会执行 `npm run test:visual`。
- 转正：第一次 Linux 比较通过并人工 review 截图 diff 后，把 `visual` job 的 `continue-on-error` 删掉，并在本节记录日期与 commit。

### docs
- `scripts/docs-check.mjs`：相对链接存在性 + 文档里 `npm run <script>` / `mvn <goal>` 的真实性（未知 Maven goal 只警告）。修复方式就是改链接或改命令，不要加豁免。
- lychee `--offline`：只校验仓库内相对链接与锚点；外链由 `lychee.toml` 的周期任务负责。

## 4. 契约 job 与破坏性变更

```bash
# 1) 用当前代码生成快照（会写 docs/api/openapi.json）
cd travel-agent-server
mvn -B -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test

# 2) 提交前确认快照变化是有意的
git diff -- docs/api/openapi.json

# 3) 重新生成前端类型
cd ../travel-agent-web && npm run api:generate && npm run typecheck && npm run api:check
```

CI 里 `git diff --exit-code -- ../docs/api/openapi.json` 把「改了 DTO 却没更新快照」变成失败；
PR 上再用 `oasdiff breaking <base>/docs/api/openapi.json <当前> --fail-on ERR` 拦破坏性变更：

```bash
git show origin/main:docs/api/openapi.json > /tmp/openapi-base.json   # 首次没有则跳过
oasdiff breaking /tmp/openapi-base.json docs/api/openapi.json --fail-on ERR
```

允许的破坏性变更（例如必需的字段删除）必须走评审并在 PR 描述里写明影响面与前端同步方案。

## 5. 安全 job

| 检查 | 触发 | 阈值 | 处置 |
| --- | --- | --- | --- |
| dependency-review | PR | `fail-on-severity: high`，只评新增依赖；**2026-11-25 起无 `continue-on-error`，为硬门禁** | 升级到修复版本，或说明为何必须引入并申请豁免 |
| `npm audit --audit-level=high` | push/PR | high + critical | 升级/low-risk fix；无修复版本时评估替代依赖 |
| Trivy（Maven 依赖） | push/PR | CRITICAL,HIGH，`ignore-unfixed` | 升级依赖；确实无修复版本则记录风险与到期时间 |
| CodeQL | push/PR/每周 | security-and-quality | 公开仓库已上传 SARIF；67 条告警分诊中，误报加抑制注释并说明 |
| gitleaks | push/PR（`secret-scan` job） | 全历史 `--log-opts=--all`，固定 8.30.1 + SHA-256，命中即失败 | 真实密钥泄露：立即轮换 → 清理历史 → 复盘 |

> CodeQL 的 SARIF 上传依赖 GitHub Advanced Security。仓库自 2026-11-24 改为 **Public** 后，
> code scanning 与上传均免费可用：仓库变量 `CODEQL_UPLOAD` 已设为 `true`，`codeql.yml` 的
> `upload` 条件成立，main 上的分析结果已入库（67 条 open 告警待分诊）。
> 历史背景：私有 Free 仓库时上传会报 `Code scanning is not enabled for this repository`，
> 当时靠 `upload: ${{ vars.CODEQL_UPLOAD == 'true' }}` 保持 workflow 绿但不上传。

gitleaks 放行范围只有四类，全部是**精确值或本地文件**：测试专用固定 JWT 密钥、明确占位词、
2026-11-25 首次 CI 实跑后逐条核实的 7 条历史测试值/占位/文档示例（见
[安全设置清单 §2.6](../operations/github-security-settings.md)），以及本地 `.env.local` 与验收证据目录
（`.gitignore` 已排除，存在时也不该阻断）。**不要**把整个 `src/` 或 `docs/` 加进 paths 放行。

## 6. 临时豁免流程

1. 优先修问题，不豁免。
2. 确需临时豁免（例如上游无修复版本的高危依赖）：
   - 在 PR 描述写清：哪条门禁、为什么现在不能修、风险、到期日期、跟踪 issue；
   - 依赖类：在 `.github/dependabot.yml` 或 scan 的 ignore 列表里加**精确到包名与原因**的条目；
   - 代码类：行内抑制注释必须带理由（Checkstyle `suppressions.xml` 的 `reason`、SpotBugs exclude 的 `reason`）；
   - 任何豁免都要在本次 PR 里同步更新对应文档。
3. 豁免只减不增，季度复核一次。

### 6.1 当前生效的豁免

| 起始 | 门禁 | 条目 | 为什么现在不能修 | 到期 | 解除条件 |
| --- | --- | --- | --- | --- | --- |
| 2026-11-24 | Trivy（`Security / 依赖漏洞`） | `CVE-2026-47884`、`CVE-2026-47890`（Spring Framework 6.2.19，CRITICAL） | 上游 6.2.x 修复版本 **6.2.20 尚未发布**（Maven Central 最新 6.2.19，Boot 3.5.16 带 6.2.19；Boot 4 / Spring 7 属主版本升级，另立项）。两条的利用前提（`XsltView` 视图渲染 / SSE view fragment）在本项目纯 JSON API 中不存在：15 个 `@RestController`、无 `@Controller`、无 `ViewResolver`、无 XSLT 资源，SSE 用 `SseEmitter` 直接写文本事件 | 2026-12-31 | 升级到含 Spring Framework 6.2.20 的 Boot 3.5.x 后，删除 `travel-agent-server/.trivyignore.yaml` 对应条目并回收本行 |

豁免文件：`travel-agent-server/.trivyignore.yaml`（结构化条目，含 `statement` 与 `expired_at`），由 `ci.yml` 的 Trivy step 通过 `trivyignores` 传入。**severity 与 exit-code 未改**，其余 HIGH/CRITICAL 仍然阻断。

## 7. Dependabot 策略

- 每周一 04:30（Asia/Shanghai）提 Maven 与 npm 的 minor/patch 分组 PR；GitHub Actions 每月一次。
- 刻意不让 bot 自动提的：Spring Boot 主版本（BOM 影响面过大）、`maplibre-gl`（包体预算与 vendor 一致性断言，见 [frontend-performance.md](frontend-performance.md)）、React 主版本。
- bot PR 与人工 PR 走同一套门禁，不特殊放行。