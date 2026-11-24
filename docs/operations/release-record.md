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

## 2026-11-24 · 依赖升级与告警收口（`5898e66`，未发布）

| 字段 | 内容 |
| --- | --- |
| 版本 / commit | `5898e66`（PR [#20](https://github.com/Lukas-Ga06/-/pull/20) 合并提交）；tag `v0.1.1` 仍指 `7eadbd1` |
| 环境变量版本 | 无新增必填项。依赖变化：`spring-boot-starter-parent` 3.5.12 → **3.5.16**（Spring Framework 6.2.19、Micrometer **1.15.12**）、`jackson-bom` → **2.21.7**；`.trivyignore.yaml` 新增两条有期限豁免 |
| 迁移版本 | 无迁移变化：仍为 V24（MySQL 23 条 `success`，V14 为 H2 专属），forward-only 不变 |
| 门禁链接 | 本机 `mvn -B clean verify` **649 tests / 0 failures / 0 errors / 3 skipped**、Checkstyle 0；main push CI（[run 45387837146](https://github.com/Lukas-Ga06/-/actions/runs/45387837146)）success |
| 真实 provider 验收 | 沿用 2026-11-23/24：高德 REST 已实测、MCP `AMAP_MAPS_API_KEY` 未验、LLM 20 连 0 failed、S3 仅本地协议桩 |
| 监控告警 | **新增（2026-11-24）**：`TravelExternalProviderDegraded{provider="amap"}` 与 `TravelExternalDegradedHigh` 在持续高德降级下真实 firing 并在降级停止后恢复，见[演练快照](../review/2026-11-24-amap-degradation-alert-drill.md)；与 2026-11-23 的 `TravelAgentServerDown` 实跑共同覆盖「进程挂了」与「单个外部依赖长期降级」两类 |
| 已知风险 | ① Spring Framework 两条 CRITICAL 为有期限豁免（`.trivyignore.yaml`，`expired_at: 2026-12-31`；上游修复 6.2.20 未发布，利用前提在本项目纯 JSON API 中不成立）；② 灰度值班人未定；③ 真机、法务、GitHub 四个安全开关、高德 MCP Key、真实 S3 仍未完成 |
| 回滚 | 回退到 `7eadbd1`（tag `v0.1.1`）；本次无迁移变化，无需恢复数据库 |
| 结论 | 依赖库 CVE 已修（两条 CRITICAL 有期限豁免）+ 外部依赖告警已实跑；**仍不构成发布**，灰度阻断项以上线清单为准 |

## 2026-11-24 · 灰度阻断项合入 main（检查点 v0.1.1，未发布）

| 字段 | 内容 |
| --- | --- |
| 版本 / commit | `7eadbd1`（PR [#17](https://github.com/Lukas-Ga06/-/pull/17) 合并提交，父 `5e5b809` + `da81878`）；tag `v0.1.1` |
| 环境变量版本 | 无新增必填项。默认值变化：`RATE_LIMIT_TRUSTED_PROXIES=172.31.240.10/32`；新增 `TRAVEL_PHOTOS_S3_PATH_STYLE`（默认 false）、`TRAVEL_PHOTOS_S3_CHECKSUM_VALIDATION`（默认 true）；`CODEQL_UPLOAD` 控制 CodeQL 上传（未开通 GHAS 时只分析不上传） |
| 迁移版本 | 无新增迁移：仍为 V24，MySQL 23 条 `success`（V14 为 H2 专属），forward-only 不变 |
| 门禁链接 | PR #17 的 11 项 check-runs 全绿（含 `Analyze (java-kotlin)` / `Analyze (javascript-typescript)`）；main push CI（[run 45379874333](https://github.com/Lukas-Ga06/-/actions/runs/45379874333)）通过 |
| 真实 provider 验收 | LLM 20 连：15 planned + 5 asked_back + 0 failed + 0 unknown，中位 30.9 s，余额 10.27 → 9.63（≈ ¥0.64），见[快照](../review/2026-11-24-gray-release-blockers.md) §1；高德 REST 已实测、MCP `AMAP_MAPS_API_KEY` 未验；S3 仅本地协议桩 |
| 备份恢复时间 | 沿用 2026-11-23 演练：48.8 MB / 28 表恢复 5.06 s、应用启动 12.75 s，RTO ≈18 s（本次未重跑） |
| 监控告警 | 沿用 2026-11-23 实跑（后端掉线 3 分钟 `TravelAgentServerDown` 真实 firing）；高德降级告警仍未实跑 |
| 已知风险 | 真机、法务、高德 MCP Key、真实 S3 端点、GitHub 四个安全开关仍未完成；`e2e-device` 观察期至 2026-12-06 |
| 回滚 | 回退到 `5e5b809`（tag `v0.1.0`）；本次无破坏性迁移，无需恢复数据库 |
| 结论 | 灰度阻断项（压测假绿 / 目的地抽取 / 限流身份 / S3 兼容 / 容器 healthcheck）已落地 `main`；**受控灰度仍缺外部验收**，不构成发布。 |

## 2026-11-23 · 灰度前检查点（未发布）

| 字段 | 内容 |
| --- | --- |
| 版本 / commit | `181df91`（分支 `feat/p0-release-readiness`；含 V24 `151cd60`） |
| 环境变量版本 | 新增 `LLM_MODEL=deepseek-flash`、模型探测开关、`users.token_version`（V24）；compose 增加 Redis/S3/限流/时区变量。模板见 `.env.production.example`，无密钥入库 |
| 迁移版本 | MySQL 8.4.10：**23 条 success，maxVersion=24**（V1–V13 + V15–V24；V14 为 H2 专属）；Hibernate `validate` 通过。V23→V24 增量 35 ms |
| 门禁 | 后端 **623 tests / 0 failures / 3 skipped**（JaCoCo/Checkstyle/SpotBugs 通过）；前端 typecheck/lint/format/532 tests/核心覆盖/包体预算/契约通过；E2E 桌面 14/14、设备 22/22、视觉 11/11；docs-check 0 断链。**远端 CI 因本机 GitHub 出口被阻断未跑** |
| 真实 provider 验收 | 高德 REST 已实测：地理编码/POI/驾车路线 `10000`，跨城同名 POI 正常，20 并发复现 9 次 `10021`；MCP `AMAP_MAPS_API_KEY` 未验。LLM 余额 ¥0.66，20 次压测未跑（需充值）。真实 iOS/Android 未验 |
| 备份恢复 | 48.8 MB / 28 表 / 142,200 行：dump 1.02 s、restore 5.06 s、校验 4.17 s、应用启动 validate 12.75 s，**RTO ≈18 s**；证据 `docs/operations/backup-restore.md` §7.2 |
| 监控告警 | Prometheus 8 组/18 条规则；停后端 3 分钟 `TravelAgentServerDown` 真实 firing 并恢复；Grafana 数据源与仪表盘可用；高德降级告警未实跑。见 [演练快照](../review/2026-11-23-monitoring-and-rollback-drill.md) |
| 回滚 | V24 加列型迁移：回退 N-1 jar 直接启动（Flyway 将 V24 视为 future、validate 通过、业务冒烟 200），**RTO ≈15 s，无需恢复数据库**；破坏性迁移仍需恢复备份 |
| 已知风险 | ① V24 上线后所有旧登录态失效一次，滚动发布窗口内可能反复掉登录，建议一次性切换；② GitHub 推送/CI/合并被本机网络阻断；③ Docker/WSL 缺失，容器冷启动未验；④ 真实高德/LLM 压测、真实 iOS、法务定稿、账号级导出未完成；⑤ `e2e-device` 观察期至 2026-12-06 |
| 结论 | **不具备公网 v1 条件；灰度前仍需真实 provider、容器冷启动与外部验收。** |