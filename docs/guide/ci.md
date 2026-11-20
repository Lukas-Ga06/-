# CI 门禁：每个 job 做什么、本地怎么复现、失败怎么处理

> 状态：当前事实 | 适用版本：0.1.0（GitHub Actions / JDK 17 / Node 20） | 最后验证：2026-11-19 | 事实来源：`.github/workflows/ci.yml`、`.github/workflows/codeql.yml`、`.github/dependabot.yml`、`.gitleaks.toml`、`lychee.toml`、`travel-agent-web/package.json`、`travel-agent-server/pom.xml`

## 1. job 总览

| job | 触发 | 关键门禁 | 本地等价 |
| --- | --- | --- | --- |
| `backend` | push/PR | `mvn verify`：测试 + JaCoCo + Checkstyle + SpotBugs | `cd travel-agent-server && mvn -B verify` |
| `mysql-migration` | push/PR | 真实 MySQL 8.4 上 Flyway 全量迁移 + Hibernate validate | `powershell -File scripts/mysql-it.ps1`（见 [mysql-migration.md](mysql-migration.md)） |
| `frontend` | push/PR | typecheck / lint / format / 覆盖率 / 包体预算 / 契约类型漂移 | `cd travel-agent-web && npm run typecheck && npm run lint && npm run format:check && npm run test:coverage && node scripts/check-core-coverage.mjs && npm run build:check && npm run api:check` |
| `contract` | push/PR | OpenAPI 快照无漂移；PR 上 oasdiff 破坏性变更 | 见第 4 节 |
| `docs` | push/PR | 相对链接存在、文档命令真实可用、lychee 本地链接 | `node scripts/docs-check.mjs` |
| `security` | push/PR | 依赖漏洞（dependency-review / npm audit / Trivy） | 见第 5 节 |
| `e2e` | push/PR | Playwright 8 条：登录/建行程/路书/PWA 资源 + 地图引擎运行时加载（`e2e/maplibre-vendor.spec.ts`） | `cd travel-agent-web && npx playwright test` |
| `CodeQL` | push/PR/每周 | Java + TypeScript SAST | 本地无法等价，靠 PR 结果（第 6 节） |

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

# 真实 MySQL 迁移（需本地实例，见 mysql-migration.md）
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
| dependency-review | PR | `fail-on-severity: high`，只评新增依赖 | 升级到修复版本，或说明为何必须引入并申请豁免 |
| `npm audit --audit-level=high` | push/PR | high + critical | 升级/low-risk fix；无修复版本时评估替代依赖 |
| Trivy（Maven 依赖） | push/PR | CRITICAL,HIGH，`ignore-unfixed` | 升级依赖；确实无修复版本则记录风险与到期时间 |
| CodeQL | push/PR/每周 | security-and-quality | 按告警修复；确认为误报时在代码里加抑制注释并说明 |
| gitleaks | 由 `.gitleaks.toml` 驱动 | 默认全仓 + 精确放行 | 真实密钥泄露：立即轮换 → 清理历史 → 复盘 |

gitleaks 放行范围只有三类：测试专用固定 JWT 密钥（正则精确匹配）、文档占位符目录、本地 `.env.local` 与验收证据目录（`.gitignore` 已排除，存在时也不该阻断）。**不要**把整个 `src/` 或 `docs/` 加进 paths 放行。

## 6. 临时豁免流程

1. 优先修问题，不豁免。
2. 确需临时豁免（例如上游无修复版本的高危依赖）：
   - 在 PR 描述写清：哪条门禁、为什么现在不能修、风险、到期日期、跟踪 issue；
   - 依赖类：在 `.github/dependabot.yml` 或 scan 的 ignore 列表里加**精确到包名与原因**的条目；
   - 代码类：行内抑制注释必须带理由（Checkstyle `suppressions.xml` 的 `reason`、SpotBugs exclude 的 `reason`）；
   - 任何豁免都要在本次 PR 里同步更新对应文档。
3. 豁免只减不增，季度复核一次。

## 7. Dependabot 策略

- 每周一 04:30（Asia/Shanghai）提 Maven 与 npm 的 minor/patch 分组 PR；GitHub Actions 每月一次。
- 刻意不让 bot 自动提的：Spring Boot 主版本（BOM 影响面过大）、`maplibre-gl`（包体预算与 vendor 一致性断言，见 [frontend-performance.md](frontend-performance.md)）、React 主版本。
- bot PR 与人工 PR 走同一套门禁，不特殊放行。