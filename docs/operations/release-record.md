# 发布记录

> 用途：每次灰度/公网发布填一条，字段与 [路线图 §6](../PROJECT_ROADMAP.md) 对齐。
> 原则：**只写可复核的事实**；未做的项写「未做」，不要用计划语气。

## 字段模板

| 字段 | 说明 |
| --- | --- |
| 版本 / commit | 镜像 tag 或 jar 的 commit，必须能 `git show` 到 |
| 环境变量版本 | `.env` 的变更摘要（不要粘贴密钥）；与 `.env.production.example` 的差异 |
| 迁移版本 | `flyway_schema_history` 的最大版本与条数；是否 forward-only |
| 门禁链接 | CI run / 本机命令与输出摘要 |
| 真实 provider 验收 | LLM / 高德 / MCP / S3 的真实调用结果；降级路径是否可见 |
| 备份恢复时间 | 最近一次备份/恢复演练的 RTO/RPO 与证据 |
| 已知风险 | 仍存在的阻断项与接受人 |
| 回滚方式 | 回滚到哪个版本、数据库是否需恢复、验证步骤 |

---

## 2026-11-23 · 灰度前检查点（未发布）

| 字段 | 内容 |
| --- | --- |
| 版本 / commit | `181df91`（分支 `feat/p0-release-readiness`；含 V24 `151cd60`） |
| 环境变量版本 | 新增 `LLM_MODEL=deepseek-flash`、模型探测开关、`users.token_version`（V24）；compose 增加 Redis/S3/限流/时区变量。模板见 `.env.production.example`，无密钥入库 |
| 迁移版本 | MySQL 8.4.10：**23 条 success，maxVersion=24**（V1–V13 + V15–V24；V14 为 H2 专属）；Hibernate `validate` 通过。V23→V24 增量 35 ms |
| 门禁 | 后端 **623 tests / 0 failures / 3 skipped**（JaCoCo/Checkstyle/SpotBugs 通过）；前端 typecheck/lint/format/532 tests/核心覆盖/包体预算/契约通过；E2E 桌面 14/14、设备 22/22、视觉 11/11；docs-check 0 断链。**远端 CI 因本机 GitHub 出口被阻断未跑** |
| 真实 provider 验收 | 未完成：高德仍为 JS Key（后端拒绝），LLM 余额未充值；模型探测 `/models` 通过（`deepseek-flash`）。真实 iOS/Android 未验 |
| 备份恢复 | 48.8 MB / 28 表 / 142,200 行：dump 1.02 s、restore 5.06 s、校验 4.17 s、应用启动 validate 12.75 s，**RTO ≈18 s**；证据 `docs/operations/backup-restore.md` §7.2 |
| 监控告警 | Prometheus 8 组/18 条规则；停后端 3 分钟 `TravelAgentServerDown` 真实 firing 并恢复；Grafana 数据源与仪表盘可用；高德降级告警未实跑。见 [演练快照](../review/2026-11-23-monitoring-and-rollback-drill.md) |
| 回滚 | V24 加列型迁移：回退 N-1 jar 直接启动（Flyway 将 V24 视为 future、validate 通过、业务冒烟 200），**RTO ≈15 s，无需恢复数据库**；破坏性迁移仍需恢复备份 |
| 已知风险 | ① V24 上线后所有旧登录态失效一次，滚动发布窗口内可能反复掉登录，建议一次性切换；② GitHub 推送/CI/合并被本机网络阻断；③ Docker/WSL 缺失，容器冷启动未验；④ 真实高德/LLM 压测、真实 iOS、法务定稿、账号级导出未完成；⑤ `e2e-device` 观察期至 2026-12-06 |
| 结论 | **不具备公网 v1 条件；灰度前仍需真实 provider、容器冷启动与外部验收。** |