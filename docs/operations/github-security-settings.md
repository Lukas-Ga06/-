# GitHub 仓库安全设置清单（人工操作）

> 状态：**已完成（2026-11-25）**：仓库已改为 **Public**；Dependency graph、Code scanning（advanced workflow，SARIF 已入库，67 条告警待分诊）、Secret scanning + Push protection（**推送拦截已实测**）、`main` 分支保护（**10** 个必需检查，含 gitleaks + 必须 PR + 禁 force push/删除）、Private vulnerability reporting + `SECURITY.md`、CI gitleaks 与 dependency-review 硬门禁全部落地；`scripts/github-security-settings.mjs` 复核 4/4 | 最后核对：2026-11-25 | 事实来源：`scripts/github-security-settings.mjs`、`.github/workflows/ci.yml`、`.github/workflows/codeql.yml`、`docs/guide/ci.md`、GitHub REST API 实测

本文是 GitHub 后台的可勾选清单，供仓库管理员按步骤开启依赖图、Dependabot、Code scanning、Secret scanning 与分支保护，并判断何时可以把 `dependency-review` 的 `continue-on-error` 去掉。

> 权限提示：这些设置需要仓库 **Admin** 权限；部分能力（私有仓库的 Code scanning / Secret scanning）取决于仓库可见性与 GitHub 订阅，页面不可用时按本文记录“不可用 + 补偿措施”，不要写成已开启。

## 0. 当前事实（先核对现状）

| 项 | 现状 | 证据 |
| --- | --- | --- |
| 工作流 | `ci.yml`、`codeql.yml`、`visual-baseline.yml`（人工触发生成 Linux 视觉基线） | `.github/workflows/` 目录 |
| CodeQL workflow | 已存在：push/PR 到 `main` + 每周一 03:17 UTC；`java-kotlin`、`javascript-typescript`；`security-and-quality` | `codeql.yml` |
| Dependency review | ✅ **硬门禁**（2026-11-25 起删除 `continue-on-error`；步骤实跑证据与负向验证见 §5） | `ci.yml` 的 `security` job |
| `e2e-device` | `continue-on-error: true`（观察中，不阻塞） | `ci.yml` |
| `visual` | `continue-on-error: true`；仓库现有基线是 Windows 版，需先用 `visual-baseline.yml` 生成 Linux 基线才真正比较 | `ci.yml`、`visual-baseline.yml` |
| Dependabot | 已配置 maven / npm / github-actions 每周检查，只提 PR 不自动合并 | `dependabot.yml` |
| 密钥扫描 | ✅ 平台侧 Secret scanning + Push protection 已开启（拦截实测见 §2.4）；CI 已加 `gitleaks` 全历史扫描（8.30.1，SHA-256 校验），与本地/改公开前的命令一致 | `.gitleaks.toml`、`ci.yml` 的 `secret-scan` job |
| Actions 生产密钥 | 当前 workflow 未引用生产密钥（MySQL service 用作业内固定口令） | 三个 workflow |
| 仓库可见性 / 订阅 | **Public**（2026-11-24 由 Private 改为公开）+ 个人 Free：公开仓库的分支保护、Code scanning、Secret scanning 均免费可用 | `PATCH /repos/Lukas-Ga06/-` → `private:false, visibility:"public"`；`security_and_analysis` 出现 secret_scanning 两项 |
| 改公开前的历史扫描 | **已扫描**：gitleaks v8.30.1 全分支全历史 166 commits / 8.61 MB，7 条命中**全部为测试值或占位**（`MCP_ENABLED=true`、两个 local-only 的 base64 测试 JWT、`INVALID_RESILIENCE_TEST_KEY_0001`、文档里的 `eyJ...xxx` 示例 token）；无 AWS/GitHub PAT/私钥类命中 | `gitleaks git . --log-opts=--all -c .gitleaks.toml`；默认规则集复核结果相同 |
| Dependency graph | **已开启**（SBOM 可读；`PATCH security_and_analysis` 幂等成功） | `GET /repos/Lukas-Ga06/-/dependency-graph/sbom` → 200（2026-11-24） |
| Dependabot alerts | **已开启** | `GET /repos/Lukas-Ga06/-/vulnerability-alerts` → 204（2026-11-24） |
| Dependabot security updates | **已开启且未暂停** | `GET /repos/Lukas-Ga06/-/automated-security-fixes` → `{"enabled": true, "paused": false}`（2026-11-24） |
| Code scanning | **已开启**（advanced workflow + SARIF 入库；仓库变量 `CODEQL_UPLOAD=true`）；已产生 **67 条 open 告警**（high 16 / medium 10 / note 33 / warning 7 / error 1） | 复跑 `run 45396658700`（attempt 2）成功；`GET /code-scanning/analyses` 显示 2026-11-24 入库 `java-kotlin` 64 条、`javascript-typescript` 3 条结果，`error` 为空；`GET /code-scanning/alerts?state=open` → 67 |
| Secret scanning / Push protection | ✅ 已开启，且 **推送拦截已实测生效**（2026-11-25：Slack/Stripe 格式被 `GH013` 拒绝；伪造 `ghp_` 未被拦的原因见 §2.6.1） | `secret_scanning.status=enabled`、`secret_scanning_push_protection.status=enabled`；复测记录见 §2.6.1 |
| 分支保护 / Ruleset | **已写入**（classic 分支保护；`main`，9 个必需检查，`strict`，`enforce_admins`，禁 force push 与删除） | `PUT /repos/Lukas-Ga06/-/branches/main/protection` → 200，`GET` 复核一致（2026-11-24） |
| Private vulnerability reporting | ✅ 已开启，并由根目录 `SECURITY.md` 指向该渠道 | `GET /private-vulnerability-reporting` → `{"enabled":true}`（2026-11-25） |

## 1. 前置确认

- [x] 确认仓库可见性（Public / Private）与订阅：**Public + 个人 Free**（2026-11-24 由 Private 改公开，改前已跑 gitleaks 全历史扫描，见 §0）；公开仓库的四项能力均免费可用。
- [x] 确认你有 Admin 权限：token 具备仓库 `admin`（`GET /repos/Lukas-Ga06/-` → `permissions.admin=true`）。
- [x] 记录执行人、日期与证据：2026-11-24；证据是 §0 的接口实测结果与 §6 表格，可用 §8 脚本一键复现。

## 2. Settings → Code security and analysis（逐项打勾）

路径：仓库 **Settings → Code security and analysis**（GitHub 新版界面可能在 **Settings → Advanced Security** 下）。

### 2.1 Dependency graph

- [x] 开启 **Dependency graph**：已于 2026-11-24 核对为已开启。
- [x] 验证：`GET /repos/Lukas-Ga06/-/dependency-graph/sbom` 返回 SPDX 清单即视为依赖图可用（不需要登录网页目测）。
- [x] 私有仓库若提示不可用：本仓库未触发该项，无需启用补偿措施。

### 2.2 Dependabot alerts

- [x] 开启 **Dependabot alerts**（依赖漏洞告警）：`GET /vulnerability-alerts` → 204，已开启。
- [x] 开启 **Dependabot security updates**（自动提修复 PR；不自动合并）：`GET /automated-security-fixes` → `enabled=true, paused=false`。
- [x] 验证：接口可读；`dependabot/*` 更新分支已在仓库中存在（2026-11-24 有 10 条），说明常规更新 PR 正常产生。
- [ ] 注意：Dependabot PR 仍需通过 CI，不能因为“安全更新”跳过门禁。

### 2.3 Code scanning（CodeQL）

仓库已有高级（advanced）CodeQL workflow，**不要同时启用 Default setup**：两者互斥，仓库里存在 CodeQL workflow 时 GitHub 会直接拒绝启用 Default setup。本仓库因此只开高级方案。

- [x] 确认 `codeql.yml` 已在默认分支 `main` 上成功跑过一次：2026-11-24 的 `main` push 运行成功（`Analyze (java-kotlin)`、`Analyze (javascript-typescript)` 均为 success）。
- [x] Settings → Code security → **Code scanning**：仓库改 Public 后已可用；`default-setup` 为 `not-configured`（**有意保持高级方案**，不点 “Set up default setup”）。
- [x] 验证：复跑 `main` 上最近一次 CodeQL 运行（`run 45396658700` attempt 2，两个 Analyze job 均 success）；`GET /code-scanning/analyses` 显示 2026-11-24 入库 `java-kotlin` 64 条、`javascript-typescript` 3 条结果且 `error` 为空；`GET /code-scanning/alerts?state=open` → **67 条**（high 16 / medium 10 / note 33 / warning 7 / error 1）。
- [ ] 告警分诊：67 条是**存量代码**的第一批结果（`java/polynomial-redos` 11 条、`java/log-injection` 7 条、`java/index-out-of-bounds` 1 条 error 等），需要单独批次分诊；CodeQL 的 check 只在分析失败时红，**告警本身不会挡住合并**。
- [x] 上传开关：仓库变量 `CODEQL_UPLOAD` 已设为 `true`（`POST /actions/variables` → 201），`codeql.yml` 的 `upload` 条件因此成立。
- [x] 在分支保护里把 CodeQL 检查设为必需（见 §3）：`Analyze (java-kotlin)` 与 `Analyze (javascript-typescript)` 已写入必需检查。

> 历史背景（保留作依据）：私有 Free 仓库时，`GET /code-scanning/default-setup` 返回 403、`PUT` 返回 404、`PATCH` 仓库安全设置返回 422，`codeql.yml` 只能设 `upload: ${{ vars.CODEQL_UPLOAD == 'true' }}` 让它绿而**不上传**。改 Public 后同一套 workflow 无需改动即恢复上传，只是把变量打开。

### 2.4 Secret scanning 与 Push protection

- [x] 开启 **Secret scanning**：已开启。仓库改 Public 时 GitHub 自动开启，`PATCH` 复核 200（`security_and_analysis.secret_scanning.status=enabled`）。
- [x] 开启 **Push protection**：开关已开启（`secret_scanning_push_protection.status=enabled`）。
- [x] 推送拦截实测：**已验证生效（2026-11-25）**。在一次性分支上提交一组多 provider 格式的假凭据后 `git push`，被 GitHub 以 **`GH013` + `GITHUB PUSH PROTECTION` + “Push cannot contain secrets”** 拒绝，并逐条指出 `Slack API Token` 与 `Stripe API Key` 的 commit/path；推送未到达远端，分支已删除。复测细节与一个**反例**（格式正确的伪造 `ghp_` token 未被拦，因 GitHub 对自身 token 有额外有效性判断，不代表开关失效）见 §2.6.1。
- [x] 历史补偿措施保留：改公开前已用 gitleaks 扫过全历史（§0）；2026-11-25 起 CI 的 `secret-scan` job 对每个 PR 跑同一套 gitleaks 全历史扫描，7 条历史命中已逐条核实并在 `.gitleaks.toml` 按精确值放行（见 §2.6.2）。

### 2.5 Private vulnerability reporting

- [x] 开启 **Private vulnerability reporting**：已开启（`GET /repos/Lukas-Ga06/-/private-vulnerability-reporting` → `{"enabled":true}`，2026-11-25）。
- [x] 在 `SECURITY.md` 写明报告渠道：根目录 [SECURITY.md](../../SECURITY.md) 指向该私密渠道，并写明响应预期、不在范围与凭据处理口径。

## 2.6 2026-11-25 复测记录

### 2.6.1 Push protection（从「未验证」转为「已验证」）

在一次性的 `test/push-protection-2026-11-25` 分支上提交含假凭据的文本文件，连续两次推送：

| 次 | 内容 | 结果 |
| --- | --- | --- |
| 1 | 伪造但格式正确的 `ghp_` + 36 位字母数字（GitHub PAT 形态） | **被接受**（exit 0）；60–75 秒后 `/secret-scanning/alerts` 仍为 0 条。GitHub 对自身 token 会做有效性判断，明显无效的伪造 token 不触发拦截——这**不是**开关失效的证据 |
| 2 | 追加 AWS `AKIA…`、fine-grained `github_pat_…`、Slack `xoxb-…`、Stripe `sk_live_…` 四种格式 | **被拒绝**：`remote: error: GH013: Repository rule violations found` → `GITHUB PUSH PROTECTION` → `Push cannot contain secrets`，逐条列出 `Slack API Token`（`push-protection-probe.txt:5`）与 `Stripe API Key`（`…:6`） |

结论：**推送拦截确实生效**，「开关已开、拦截未验证」这条记录到此关闭。一次性分支与本地分支均已删除，未合并任何内容。

### 2.6.2 CI gitleaks 的 7 条历史命中

`secret-scan` job 第一次实跑命中 7 条（168 commits / 8.65 MB），逐条核实**全部是测试值/占位/文档示例**：

| 文件 | 内容 | 判断 |
| --- | --- | --- |
| `.env.production.example:109` | `MCP_ENABLED=true` | `generic-api-key` 误判布尔开关 |
| `.e2e/dark/start-instance.mjs:26`、`.e2e/resilience/manage-instance.mjs:30` | base64 JWT secret（值本身写明 local-only） | 本地测试用 |
| `.e2e/acceptance/evidence/resilience.json:766`、`docs/review/2026-11-19-resilience.md:155` | `INVALID_RESILIENCE_TEST_KEY_0001` | 显式无效占位 |
| `docs/guide/api-reference.md:142,156` | `eyJhbGciOiJIUzI1NiJ9.xxx` | 文档示例 token |

已按「精确值放行、不做目录级豁免」原则写入 `.gitleaks.toml`；本地复扫 0 命中，CI 转绿。

## 3. Settings → Branches → 分支保护（main）

> **已完成（2026-11-24）**：仓库改 Public 后 `PUT /repos/Lukas-Ga06/-/branches/main/protection` 返回 200，规则已生效，`GET` 复核一致（`strict:true`、9 个 contexts、`enforce_admins:true`、`allow_force_pushes:false`、`allow_deletions:false`）。下面的清单保留为已执行记录：`node scripts/github-security-settings.mjs --apply` 就是把它们一次性写进去的那条命令。

- [ ] 添加 `main` 的分支保护规则（或 Ruleset）。
- [ ] 勾选 **Require a pull request before merging**（至少 0 个审批也可，但必须走 PR；建议 1 个审批）。
- [ ] 勾选 **Require status checks to pass before merging**，并勾选 **Require branches to be up to date before merging**。
- [ ] 必需检查（名称已用 `GET /commits/main/check-runs` 逐字核对，**必须含全角括号**，以 Actions 页面实际显示为准）：

| 必需 | 检查名（`ci.yml` / `codeql.yml`） |
| --- | --- |
| ✅ | `Backend / mvn verify（测试 + 覆盖率 + Checkstyle + SpotBugs）` |
| ✅ | `Backend / 真实 MySQL Flyway 迁移 + Hibernate validate` |
| ✅ | `Frontend / typecheck + lint + format + coverage + build budget + 契约类型` |
| ✅ | `Contract / OpenAPI 快照一致 + 破坏性变更` |
| ✅ | `Docs / 链接与命令引用校验` |
| ✅ | `Security / 依赖漏洞` |
| ✅ | `Security / gitleaks 密钥扫描`（2026-11-25 起，见 §2.6.2） |
| ✅ | `E2E / Playwright smoke（后端 H2 + 前端 preview）` |
| ✅ | `Analyze (java-kotlin)` |
| ✅ | `Analyze (javascript-typescript)` |
| ⬜ 暂不必需 | `E2E / 弱网(CDP) + 多端并发（隔离后端，观察中）`（转正条件见 `docs/guide/ci.md`） |
| ⬜ 暂不必需 | `Visual / 10 页截图回归（Linux 基线，观察中）`（需先生成并提交 Linux 基线） |

- [ ] 勾选 **Require conversation resolution before merging**。
- [ ] 勾选 **Do not allow bypassing the above settings**（至少对管理员也生效；脚本用 `enforce_admins: true`）。
- [ ] 勾选 **Restrict force pushes** / **Disable force push**（脚本用 `allow_force_pushes: false`）。
- [ ] （建议）勾选 **Require linear history**，或在团队约定里说明不强制（脚本未开，保持团队约定）。
- [ ] （可选）**Require signed commits** 视团队密钥管理能力决定。
- [x] 验证（直接 push）：`git push origin main` 被拒——`GH006: Protected branch update failed`，附「Changes must be made through a pull request」与「9 of 9 required status checks are expected」（2026-11-24 实测；用空提交验证后已 `reset --soft` 回滚，未留痕）。
- [x] 验证（PR 流程，2026-11-25）：#23–#26 四个 PR 都必须在检查全绿后才能合并；`strict` 使落后于 `main` 的分支先 `update-branch` 并重跑检查（#24/#25/#26 各经历一次），符合「必须 PR + 必须最新 + 必需检查」的预期。

## 4. Settings → Actions → General（建议）

- [ ] **Workflow permissions** 选 “Read repository contents and packages permissions”（最小权限）。
- [ ] 关闭 “Allow GitHub Actions to create and approve pull requests”。
- [ ] Fork PR：“Require approval for all outside collaborators” 或更严格。
- [ ] 若未来在 Actions 中配置部署密钥：使用 **Environment** + Required reviewers + 仅保护分支可触发；不要把生产 `.env` 放进 artifact 或日志。
- [ ] 验证：新建一个无权限 PR，确认 workflow 使用只读 token。

## 5. `dependency-review` 的 `continue-on-error` 何时可以删除

> **已完成（2026-11-25）**：`continue-on-error` 已删除（PR #24），并在 #24 的 job 日志里确认步骤真实执行（`Dependency review did not detect any vulnerable packages with severity level "high" or higher.`，不是跳过）。#27 作为一次性负向测试 PR 引入 `minimist@1.2.5`，`Security / 依赖漏洞` job 变红——红在兜底的 `npm audit`（`1 critical`），dependency-review 本身在本次扫描里未判 high+（PR head 的依赖图快照时序问题，已记录在 #27 评论）；验证后 PR 已关闭、未合并。下面「删除条件 / 步骤」保留为执行记录。

当前代码（`ci.yml` → `security` job）：

```yaml
      - name: Dependency review（PR 新增依赖）
        if: github.event_name == 'pull_request'
        continue-on-error: true
        uses: actions/dependency-review-action@v4
```

删除条件（**全部满足**才删）：

1. **Dependency graph 已开启**（§2.1），且 `Security → Dependency graph` 页面正常。
2. 在一个**测试 PR** 上，`Dependency review` 步骤日志里不再出现 `Dependency review is not supported on this repository`，且步骤真实执行（不是跳过）。
3. 该测试 PR 不引入高危依赖时步骤为绿；另开一个**一次性分支**故意引入一个已知 high 级漏洞依赖，确认步骤变红（验证失败路径真的生效；验证后关闭 PR，不合并）。
4. 确认 fork PR 也能拿到必要权限（`pull-requests: read` 已在 job 中声明；若 fork 场景仍报权限错误，先解决再删）。
5. 删除后把 `Dependency review` 加入分支保护必需检查（§3 表格可增补）。

删除步骤：

- [ ] 新建单独 PR，只删 `continue-on-error: true`（不改其他逻辑），在 PR 里附上第 2–3 条的证据。
- [ ] 合并后观察 1–2 个 PR：步骤失败时确实阻塞合并。
- [ ] 若连续失败且确认为平台问题，回滚该行并记录原因与期限。

> 在条件满足前保留 `continue-on-error` 是**有意降级**，不是遗漏：它避免“仓库设置项没开”把整条 PR 门禁卡死。真正的依赖门禁仍是同 job 的 `npm audit` 与 Trivy。

## 6. 验证明细与证据表

| 设置 | 页面路径 | 实际状态（2026-11-24） | 执行人 / 日期 | 证据 |
| --- | --- | --- | --- | --- |
| Dependency graph | Settings → Code security | ✅ Enabled | 2026-11-24 | `GET /repos/Lukas-Ga06/-/dependency-graph/sbom` → 200（SPDX 清单） |
| Dependabot alerts | 同上 | ✅ Enabled（已有 3 条 medium 告警） | 2026-11-24 | `GET /vulnerability-alerts` → 204；`GET /dependabot/alerts?state=open` → 3 |
| Dependabot security updates | 同上 | ✅ Enabled（未暂停） | 2026-11-24 | `GET /automated-security-fixes` → `{"enabled":true,"paused":false}` |
| Code scanning（advanced） | 同上 + Actions | ✅ Enabled，SARIF 已入库，67 条 open 告警 | 2026-11-24 | `GET /code-scanning/analyses` → 2026-11-24 两条分析（java 64 / js 3，`error` 空）；`GET /code-scanning/alerts?state=open` → 67 |
| Secret scanning | 同上 | ✅ 开关 Enabled；推送拦截已验证（§2.6.1） | 2026-11-25 | `secret_scanning.status=enabled`；拦截实测见 §2.6.1 |
| Push protection | 同上 | ✅ **拦截已验证**（`GH013` 拒绝 Slack/Stripe 格式） | 2026-11-25 | `secret_scanning_push_protection.status=enabled`；探测推送被拒，分支未落远端 |
| 分支保护 | Settings → Branches | ✅ 已写入（strict + **10** 个必需检查 + 禁 force push/删除 + 对管理员生效） | 2026-11-25 | `PUT /branches/main/protection` → 200；`GET` 复核一致 |
| Private vulnerability reporting | Settings → Code security | ✅ Enabled + `SECURITY.md` 指向私密渠道 | 2026-11-25 | `GET /private-vulnerability-reporting` → `{"enabled":true}` |
| Actions 权限 | Settings → Actions → General | ⬜ 本次未核对 | `[ ]` | 见 §4，需人工确认 default workflow 权限 |
| dependency-review 转硬门禁 | `ci.yml` | ✅ 已执行（PR #24；#27 负向验证后关闭） | 2026-11-25 | 步骤实跑日志 + #27 的 job 变红 |
| CI gitleaks | `ci.yml` | ✅ 已执行（`secret-scan` job，8.30.1 + SHA-256） | 2026-11-25 | 7 条历史命中按精确值放行，复扫 0 命中（§2.6.2） |

## 7. 未完成 / 不在本任务范围

| 项 | 状态 |
| --- | --- |
| 仓库可见性 | **已改为 Public**（2026-11-24；改前 gitleaks 全历史扫描，命中项均为测试值/占位） |
| Dependency graph / Dependabot alerts / security updates | **已完成并核对**（2026-11-24） |
| Code scanning | **已开启**：高级 workflow + SARIF 入库；已有 **67 条 open 告警**待分诊 |
| Secret scanning / Push protection | 开关**已开启**且**推送拦截已验证**（2026-11-25，§2.6.1）；平台告警仍为 0 条（拦截发生在入库前，属预期） |
| 分支保护（main） | **已写入并复核**：strict + **10** 个必需检查（含 gitleaks）+ 禁 force push/删除 + 对管理员生效 |
| 改用 CodeQL Default setup | **未执行且不建议**：与高级 workflow 互斥，需先删 `codeql.yml`，且默认套件弱于现在的 `security-and-quality` |
| 67 条 CodeQL 告警的分诊 | **进行中**：分诊记录与修复单列批次（`docs/review/2026-11-25-codeql-triage.md`）；CodeQL check 不会因告警变红，要"有告警就不许合并"需另配 |
| 删除 `dependency-review` 的 `continue-on-error` | **已完成（2026-11-25，#24）**：步骤实跑 + #27 负向验证后关闭 |
| 在 CI 增加 gitleaks 步骤 | **已完成（2026-11-25，#25）**：`secret-scan` job，8.30.1 + SHA-256，7 条历史命中精确放行（§2.6.2） |
| `e2e-device` 转硬门禁 | **未执行**（观察期） |
| `visual` 转硬门禁 | **未执行**：Linux 基线已提交，仍在观察稳定性 |
| `SECURITY.md` | **已完成（2026-11-25，#26）**：根目录文件指向已开启的 Private vulnerability reporting |
| 本文档与 `scripts/github-security-settings.mjs` 的改动 | **已提交（2026-11-25 同步 PR）**：`REQUIRED_CHECKS` 增加 gitleaks，合并后 `--apply` 写入 10 项必需检查 |

## 8. 核对与解锁（一条命令）

`scripts/github-security-settings.mjs` 是本文清单的 API 版：默认只核对、不动任何开关，加 `--apply` 才写入。

```bash
node scripts/github-security-settings.mjs           # 核对：逐项打印 ✓ / ⛔ / ·，退出码 0 / 2 / 1
node scripts/github-security-settings.mjs --apply   # 写入：Dependency graph、Secret scanning + Push protection、main 分支保护
```

- Token：`GH_TOKEN` / `GITHUB_TOKEN`，缺省时读本机 `git credential`（github.com）；需要仓库 Admin 权限，脚本会先校验。
- 退出码：`0` 四项全部达成；`2` 有项目被订阅或可见性挡住；`1` 其他错误（网络/权限/参数）。
- 分支保护的必需检查清单、`enforce_admins`、禁止 force push 与删除，都写死在脚本里，与 §3 表格逐条对应。
- CodeQL Default setup 默认**不**尝试（与高级 workflow 互斥）；确需切换时加 `--enable-default-codeql`，并先删除 `.github/workflows/codeql.yml`。
- 2026-11-24 执行结果：`4/4 项达成，0 项未达成，0 项错误`（退出码 0）。

### 8.1 本次执行记录（2026-11-24）

| 步骤 | 做法 | 结果 |
| --- | --- | --- |
| 决策 | 在「改 Public」与「升级订阅」之间选了 **A：仓库改为 Public** | 已执行，`private:false, visibility:public` |
| 公开前历史扫描 | gitleaks v8.30.1，全分支全历史（`--log-opts=--all`），仓库自带 `.gitleaks.toml` + 默认规则集双跑 | 166 commits / 8.61 MB，7 条命中全部为测试值或占位；无 AWS/GitHub PAT/私钥类命中 |
| 开启 | `PATCH` 仓库安全设置 + `PUT` 分支保护 + `POST` 仓库变量 `CODEQL_UPLOAD=true` | 全部 2xx/201 |
| 复核 | `node scripts/github-security-settings.mjs` | 4/4，退出码 0 |

### 8.2 2026-11-25 收口记录

| 步骤 | 做法 | 结果 |
| --- | --- | --- |
| dependency-review 硬门禁 | PR #24 删除 `continue-on-error`；#27 一次性负向 PR 引入 `minimist@1.2.5` 验证 | #24 合并；#27 的 `Security / 依赖漏洞` job 变红后关闭（红在 npm audit 兜底） |
| CI gitleaks | PR #25 增加 `secret-scan` job（8.30.1 + SHA-256） | 合并；首次实跑 7 条历史命中按精确值放行后转绿 |
| SECURITY.md | PR #26 增加根目录政策并指向 PVR | 合并；PVR 已开启 |
| push protection 复测 | 一次性分支推送多 provider 假凭据 | Slack/Stripe 格式被 `GH013` 拒绝；见 §2.6.1 |
| 必需检查 | 本同步 PR 更新 `REQUIRED_CHECKS` 后 `--apply` | 10 个必需检查（新增 `Security / gitleaks 密钥扫描`） |

> 许可提醒：仓库现在**公开可见**，但 `LICENSE` 仍是专有许可——公开只改变了可见性，不授予他人使用、复制、修改的权利。若打算真正开源，需要另行更换许可证。

> 推送拦截的实测结论见 §2.4：开关打开 ≠ 已观察到拦截，本文按"未验证"记录，不用"已验证"糊过去。

相关文档：[CI 说明](../guide/ci.md) · [故障处理手册](runbook.md) · [生产配置清单](production-config.md) · [PR 模板](../../.github/pull_request_template.md)