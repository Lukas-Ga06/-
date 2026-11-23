# GitHub 仓库安全设置清单（人工操作）

> 状态：**待人工执行**（代码与文档已就绪，仓库设置无法由本仓库代码自动完成） | 最后核对：2026-11-22 | 事实来源：`.github/workflows/ci.yml`、`.github/workflows/codeql.yml`、`.github/dependabot.yml`、`.gitleaks.toml`、`docs/guide/ci.md`

本文是 GitHub 后台的可勾选清单，供仓库管理员按步骤开启依赖图、Dependabot、Code scanning、Secret scanning 与分支保护，并判断何时可以把 `dependency-review` 的 `continue-on-error` 去掉。

> 权限提示：这些设置需要仓库 **Admin** 权限；部分能力（私有仓库的 Code scanning / Secret scanning）取决于仓库可见性与 GitHub 订阅，页面不可用时按本文记录“不可用 + 补偿措施”，不要写成已开启。

## 0. 当前事实（先核对现状）

| 项 | 现状 | 证据 |
| --- | --- | --- |
| 工作流 | `ci.yml`、`codeql.yml`、`visual-baseline.yml`（人工触发生成 Linux 视觉基线） | `.github/workflows/` 目录 |
| CodeQL workflow | 已存在：push/PR 到 `main` + 每周一 03:17 UTC；`java-kotlin`、`javascript-typescript`；`security-and-quality` | `codeql.yml` |
| Dependency review | 已配置，但带 `continue-on-error: true`（未启用 Dependency graph 时跳过） | `ci.yml` 的 `security` job |
| `e2e-device` | `continue-on-error: true`（观察中，不阻塞） | `ci.yml` |
| `visual` | `continue-on-error: true`；仓库现有基线是 Windows 版，需先用 `visual-baseline.yml` 生成 Linux 基线才真正比较 | `ci.yml`、`visual-baseline.yml` |
| Dependabot | 已配置 maven / npm / github-actions 每周检查，只提 PR 不自动合并 | `dependabot.yml` |
| 密钥扫描 | 仓库有 `.gitleaks.toml`，但 **CI 中没有 gitleaks 步骤**；是否扫描取决于 GitHub Secret scanning 是否开启 | `.gitleaks.toml`、`ci.yml` |
| Actions 生产密钥 | 当前 workflow 未引用生产密钥（MySQL service 用作业内固定口令） | 三个 workflow |

## 1. 前置确认

- [ ] 确认仓库可见性（Public / Private）与订阅；私有仓库的 Code scanning、Secret scanning 可能需要 GitHub Advanced Security / Secret Protection，页面会直接提示不可用。
- [ ] 确认你有 Admin 权限。
- [ ] 记录执行人、日期与证据链接；不要只点开关不留记录。

## 2. Settings → Code security and analysis（逐项打勾）

路径：仓库 **Settings → Code security and analysis**（GitHub 新版界面可能在 **Settings → Advanced Security** 下）。

### 2.1 Dependency graph

- [ ] 开启 **Dependency graph**。
- [ ] 验证：Insights → Dependency graph 能看到依赖清单；Security → Dependabot 能看到依赖图。
- [ ] 私有仓库若提示不可用：记录原因，改为在 CI 中保留 `npm audit` 与 Trivy（已有）。

### 2.2 Dependabot alerts

- [ ] 开启 **Dependabot alerts**（依赖漏洞告警）。
- [ ] 开启 **Dependabot security updates**（自动提修复 PR；不自动合并）。
- [ ] 验证：Security → Dependabot alerts 页面可打开；`.github/dependabot.yml` 的常规更新 PR 能正常出现。
- [ ] 注意：Dependabot PR 仍需通过 CI，不能因为“安全更新”跳过门禁。

### 2.3 Code scanning（CodeQL）

仓库已有高级（advanced）CodeQL workflow，**不要同时启用 Default setup**（两者会冲突或产生重复分析）。

- [ ] 确认 `codeql.yml` 已在默认分支 `main` 上成功跑过一次（Actions → CodeQL → 最近一次 `main` 运行成功）。
- [ ] Settings → Code security → **Code scanning** 显示 “Advanced” / 已由 workflow 上传结果；不要点 “Set up default setup”。
- [ ] 验证：Security → Code scanning alerts 有结果页（可以为 0 条告警，但页面存在且关联到 CodeQL）。
- [ ] 在分支保护里把 CodeQL 检查设为必需（见 §3）。

### 2.4 Secret scanning 与 Push protection

- [ ] 开启 **Secret scanning**（若页面可用）。
- [ ] 开启 **Push protection**（若页面可用）。
- [ ] 私有仓库/订阅不支持时：记录“不可用”，补偿措施为：
  - 在 `security` job 增加 gitleaks 步骤（当前**没有**，需单独 PR，不在本次文档任务范围）；
  - 依赖 `.gitignore` + 提交前人工检查 + 代码评审；
  - 禁止把真实 `.env` 上传为 artifact。
- [ ] 验证：Settings 页面显示已开启；试推一个明显的假测试串（不要在真实分支留痕）确认拦截，或直接记录“未验证推送拦截”。

### 2.5 Private vulnerability reporting（建议）

- [ ] 开启 **Private vulnerability reporting**，让外部研究者可私下报告漏洞。
- [ ] 在 `SECURITY.md` 或 README 写明报告渠道（当前仓库没有 `SECURITY.md`，可作为后续补充）。

## 3. Settings → Branches → 分支保护（main）

- [ ] 添加 `main` 的分支保护规则（或 Ruleset）。
- [ ] 勾选 **Require a pull request before merging**（至少 0 个审批也可，但必须走 PR；建议 1 个审批）。
- [ ] 勾选 **Require status checks to pass before merging**，并勾选 **Require branches to be up to date before merging**。
- [ ] 必需检查（名称以 Actions 页面实际显示为准）：

| 必需 | 检查名（`ci.yml` / `codeql.yml`） |
| --- | --- |
| ✅ | `Backend / mvn verify（测试 + 覆盖率 + Checkstyle + SpotBugs）` |
| ✅ | `Backend / 真实 MySQL Flyway 迁移 + Hibernate validate` |
| ✅ | `Frontend / typecheck + lint + format + coverage + build budget + 契约类型` |
| ✅ | `Contract / OpenAPI 快照一致 + 破坏性变更` |
| ✅ | `Docs / 链接与命令引用校验` |
| ✅ | `Security / 依赖漏洞` |
| ✅ | `E2E / Playwright smoke（后端 H2 + 前端 preview）` |
| ✅ | `Analyze (java-kotlin)` |
| ✅ | `Analyze (javascript-typescript)` |
| ⬜ 暂不必需 | `E2E / 移动端(WebKit) + 弱网(CDP) + 多端并发（隔离后端，观察中）`（转正条件见 `docs/guide/ci.md`） |
| ⬜ 暂不必需 | `Visual / 11 页截图回归（Linux 基线，观察中）`（需先生成并提交 Linux 基线） |

- [ ] 勾选 **Require conversation resolution before merging**。
- [ ] 勾选 **Do not allow bypassing the above settings**（至少对管理员也生效）。
- [ ] 勾选 **Restrict force pushes** / **Disable force push**。
- [ ] （建议）勾选 **Require linear history**，或在团队约定里说明不强制。
- [ ] （可选）**Require signed commits** 视团队密钥管理能力决定。
- [ ] 验证：用一个测试 PR 确认：缺检查时不能合并；检查全绿后可合并；直接 push 到 `main` 被拒绝。

## 4. Settings → Actions → General（建议）

- [ ] **Workflow permissions** 选 “Read repository contents and packages permissions”（最小权限）。
- [ ] 关闭 “Allow GitHub Actions to create and approve pull requests”。
- [ ] Fork PR：“Require approval for all outside collaborators” 或更严格。
- [ ] 若未来在 Actions 中配置部署密钥：使用 **Environment** + Required reviewers + 仅保护分支可触发；不要把生产 `.env` 放进 artifact 或日志。
- [ ] 验证：新建一个无权限 PR，确认 workflow 使用只读 token。

## 5. `dependency-review` 的 `continue-on-error` 何时可以删除

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

| 设置 | 页面路径 | 期望状态 | 执行人 / 日期 | 证据（截图/链接） |
| --- | --- | --- | --- | --- |
| Dependency graph | Settings → Code security | Enabled | `[ ]` | `[ ]` |
| Dependabot alerts | 同上 | Enabled | `[ ]` | `[ ]` |
| Dependabot security updates | 同上 | Enabled | `[ ]` | `[ ]` |
| Code scanning（advanced） | 同上 + Actions | Workflow 结果已上传 | `[ ]` | `[ ]` |
| Secret scanning | 同上 | Enabled 或记录不可用 | `[ ]` | `[ ]` |
| Push protection | 同上 | Enabled 或记录不可用 | `[ ]` | `[ ]` |
| 分支保护 | Settings → Branches | 必需检查全绿才可合并 | `[ ]` | `[ ]` |
| Actions 权限 | Settings → Actions → General | 只读 token | `[ ]` | `[ ]` |
| dependency-review 转硬门禁 | `ci.yml` | 条件满足后删除 `continue-on-error` | `[ ]` | `[ ]` |

## 7. 未完成 / 不在本任务范围

| 项 | 状态 |
| --- | --- |
| 上述 GitHub 后台开关 | **全部待人工执行**（本文只给清单） |
| 删除 `dependency-review` 的 `continue-on-error` | **未执行**（需单独 PR + 验证证据） |
| 在 CI 增加 gitleaks 步骤 | **当前不存在**；需 CI 维护方单独 PR |
| `e2e-device` 转硬门禁 | **未执行**（观察期） |
| `visual` 转硬门禁 | **未执行**：先跑 `visual-baseline.yml` 生成并提交 `*-chromium-linux.png`，再观察稳定性 |
| `SECURITY.md` | **不存在**，建议补充漏洞报告渠道 |

相关文档：[CI 说明](../guide/ci.md) · [故障处理手册](runbook.md) · [生产配置清单](production-config.md) · [PR 模板](../../.github/pull_request_template.md)