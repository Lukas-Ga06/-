# 运维与合规文档索引

> 状态：当前事实 | 最后更新：2026-11-22 | 事实来源：`docs/operations/`、`docs/legal/`、`docs/guide/deployment.md`

本目录放**上线与值班可直接照做**的运维资产：告警规则、故障处置、生产配置、备份恢复与仓库安全设置。合规类文档在 [`docs/legal/`](../legal/README.md)。

## 1. 文档清单

| 文档 | 用途 | 对应路线图 |
| --- | --- | --- |
| [runbook.md](runbook.md) | 告警处置手册（与 `alerts.yml` 的 `runbook_url` 一一对应），含日志脱敏、限流/配额验证、告警矩阵补充 | P0-D |
| [alerts.yml](alerts.yml) | Prometheus 告警规则（PrometheusRule / rule_files） | P0-D |
| [grafana-travel-agent.json](grafana-travel-agent.json) | Grafana Dashboard（指标面板） | P0-D |
| [production-config.md](production-config.md) | 生产环境变量清单、密钥权限/配额/轮换、未配置时的预期行为、compose 透传核对（含仍不可用 `.env` 覆盖的项）、上线自检 | P0-A |
| [backup-restore.md](backup-restore.md) | MySQL 8.4 备份/恢复、Flyway 迁移前快照与 V23 后复跑、照片/S3 对象备份、RTO/RPO 记录模板、回滚边界 | P0-D |
| [github-security-settings.md](github-security-settings.md) | GitHub 依赖图/Dependabot/Code scanning/Secret scanning/分支保护的人工操作清单，以及 `dependency-review` 转硬门禁的判定 | P0-C / P0-D |
| [launch-checklist.md](launch-checklist.md) | 上线执行清单（Android 真机口径）：12 项阻断差距、阶段 0–3 步骤、Android 真机验收矩阵、风险登记 | P0-A～P0-D / P1-F |

配套脚本与模板：

| 路径 | 说明 |
| --- | --- |
| [`scripts/backup-drill.ps1`](../../scripts/backup-drill.ps1) | 备份 + 恢复到临时库 + 关键表计数对比；默认只 dump，不执行破坏性操作 |
| [`scripts/mysql-it.ps1`](../../scripts/mysql-it.ps1) | 真实 MySQL Flyway 迁移测试（一次性库） |
| [`scripts/docs-check.mjs`](../../scripts/docs-check.mjs) | 文档链接/命令校验（CI 与本地都跑） |
| [`../../.env.production.example`](../../.env.production.example) | 生产环境变量模板（只有占位符，不含真实密钥） |
| [`.github/pull_request_template.md`](../../.github/pull_request_template.md) | PR 模板（含 WebKit 模拟 ≠ iOS 真机的声明） |

## 2. 使用顺序

1. **上线前**：按 [production-config.md](production-config.md) §6 逐项打勾，并用 `docker compose config` 渲染确认变量真的进了容器。
2. **迁移前**：按 [backup-restore.md](backup-restore.md) §4 做快照，并在真实 MySQL 8.4 复跑 `MySqlFlywayMigrationTest`。
3. **发布后**：按 [runbook.md](runbook.md) 附录 C 核对告警是否真的送达，附录 A 做一次日志脱敏检查。
4. **仓库设置**：按 [github-security-settings.md](github-security-settings.md) 由管理员开启并留证据。
5. **合规**：隐私政策、第三方服务、数据删除与许可证见 [docs/legal/](../legal/README.md)；发布前需法务签字。

## 3. 维护约定

- 新增运维文档必须挂到本索引，并在 `docs/guide/README.md` 的“运维与合规”表加一行。
- 改文档后运行 `node scripts/docs-check.mjs`，0 断链、0 失效命令。
- 标注规则沿用 [文档生命周期](../guide/documentation.md)：写清“当前事实 / 最后验证 / 事实来源”，区分“代码已实现”“自动化已验证”“真实环境已验证”“尚未验证”。
- 演练记录（RTO/RPO、备份恢复、告警送达）必须附日期、执行人、证据路径；没有执行的不要写成已通过。

相关入口：[部署与运维](../guide/deployment.md) · [可观测性](../guide/observability.md) · [合规文档](../legal/README.md) · [根 README](../../README.md)