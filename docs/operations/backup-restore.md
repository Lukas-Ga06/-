# 备份、恢复与迁移演练（P0-D）

> 状态：**已在真实 MySQL 8.4 完成一次备份→恢复→校验演练（2026-11-23，单机合成数据，见 §7.2）；生产类环境与照片/S3 仍未演练** | 适用版本：MySQL 8.4 / Flyway V1–V24 / Spring Boot 3.5.12 | 最后核对：2026-11-23 | 事实来源：`docker-compose.yml`、`travel-agent-server/src/main/resources/db/`、`application-mysql.yml`、`docs/guide/mysql-migration.md`、`docs/guide/deployment.md` §10、[路线图](../PROJECT_ROADMAP.md) P0-D

## 0. 结论与边界（先看这里）

- 本文给出 MySQL 8.4 逻辑备份、恢复、迁移前快照、迁移后复跑、照片/对象存储备份、校验与 RTO/RPO 记录模板。
- 可执行脚本：[`scripts/backup-drill.ps1`](../../scripts/backup-drill.ps1)（PowerShell 7 / Windows，参数化；默认只 dump，不执行破坏性操作）。
- **已有一次真实演练结果（2026-11-23，MySQL 8.4.10 / 48.8 MB 合成数据）**：PASS，21 张关键表行数全等、Flyway 22/22、应用 `validate` 通过；照片/S3、生产类网络与磁盘仍未演练。§7.2 中未填的行不得当作已通过。
- 迁移是 **forward-only**：仓库不提供 `down` 脚本，回滚数据库只能靠“恢复备份”或“旧版本代码仍能读新结构”。发布前必须确认这一点（见 §8）。
- 备份不包括对象存储里的照片原文件；本地照片在 `backend-data` 卷，S3 照片需对象存储自身的备份/版本控制。

---

## 1. 备份对象与优先级

| 对象 | 内容 | 优先级 | 备份方式 |
| --- | --- | --- | --- |
| MySQL `travel_agent` 库 | 用户、行程、记账、会话、待确认方案、LLM 配额、RAG、追踪等全部业务数据 | **最高** | `mysqldump` 逻辑备份（本文 §2） |
| 照片文件 | `trip_photo.stored_name` 对应的原图 | 高（不可再生） | 本地卷 `backend-data` 打包；S3 用版本控制/跨区复制 |
| Redis | 限流计数（compose 启用 AOF，`redis-data` 卷） | **低（可丢）** | 不纳入 RTO/RPO 关键数据；丢失只让窗口计数清零，不影响业务；需要保留时可复制 `redis-data` 卷 |
| 容器日志 | 排障证据 | 低 | `backend` / `web` 的 `json-file` 已轮转（10m × 3）；日志不属于业务备份，需要时单独归档 |
| 知识库 | `rag_document` / `rag_document_version` / `rag_chunk` 在 MySQL 内 | 中（可从 `knowledge.json` 重建公共知识） | 随 MySQL 备份；个人知识库不可重建 |
| 配置与密钥 | `.env`、`docker-compose.override.yml`、证书 | 高（但**不能**入库） | 密钥管理系统/受控离线备份 |
| 应用镜像 | 当前发布版本 | 中（可重新构建） | 保留旧镜像 tag 以便回滚 |

## 2. MySQL 8.4 逻辑备份

在仓库根目录（compose 部署）执行，产物落在宿主 `backups/`：

```bash
mkdir -p backups
STAMP=$(date +%Y%m%d-%H%M%S)
docker compose exec -T mysql \
  mysqldump -uroot -p"${MYSQL_ROOT_PASSWORD}" \
  --single-transaction --routines --triggers \
  --default-character-set=utf8mb4 --no-tablespaces \
  travel_agent | gzip > "backups/travel_agent-${STAMP}.sql.gz"

# 记录校验和与版本证据（必须与备份一起保存）
sha256sum "backups/travel_agent-${STAMP}.sql.gz" > "backups/travel_agent-${STAMP}.sql.gz.sha256"
docker compose exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -N -e \
  "SELECT @@version; SELECT MAX(version), COUNT(*) FROM travel_agent.flyway_schema_history WHERE success=1;" \
  > "backups/travel_agent-${STAMP}.meta.txt"
```

要点：

- `--single-transaction` 对 InnoDB 提供一致性快照，**不锁表**；不要用 `--lock-tables` 替代。
- `--routines --triggers` 覆盖存储过程/触发器（当前 schema 可能没有，但保持口径一致）。
- 密码不要出现在 shell 历史：生产建议用受保护的 `defaults-extra-file` 或密钥管理系统注入；`--no-tablespaces` 避免无 `PROCESS` 权限时报错。
- 备份文件与校验和、元数据（MySQL 版本、Flyway 最大版本、行数摘要）放在同一目录，作为恢复依据。
- 保留策略（建议，需运营确认）：每日 1 份保留 7 天 + 每周 1 份保留 4 周 + 每月 1 份保留 12 个月；异地存放；加密存储；备份目录 `umask 077`。
- 定时任务示例见 [部署与运维 §10.2](../guide/deployment.md)。

## 3. 恢复到临时库（默认演练方式）

**永远先恢复到临时库**，校验通过后再决定是否动生产库：

```bash
RESTORE_DB="travel_agent_restore_$(date +%Y%m%d%H%M%S)"

docker compose exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" \
  -e "CREATE DATABASE \`${RESTORE_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

gunzip -c "backups/travel_agent-<时间戳>.sql.gz" | \
  docker compose exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" "${RESTORE_DB}"

# 校验（见 §6），确认无误后清理临时库
# DROP DATABASE `travel_agent_restore_...`;
```

恢复生产库的顺序（仅在灾难恢复时）：

1. **停止写入**：`docker compose stop backend`（避免边恢复边写）。
2. 再备份一次当前库（哪怕它已损坏，保留现场）。
3. 新建一个恢复库（如 `travel_agent_restored_<ts>`）按上面的 `gunzip | mysql` 恢复；**不要**直接覆盖可疑的旧库。MySQL 没有 `RENAME DATABASE`，不要尝试“改库名”这种操作。
4. 用 `docker-compose.override.yml` 把 `MYSQL_DATABASE` 临时指向恢复库，启动 backend，让 Flyway 校验 `flyway_schema_history`；应用启动成功即 `validate` 通过。
5. 按 §6 校验，再按 [部署与运维 §3.4](../guide/deployment.md) 跑 10 项清单。
6. 确认无误后，把恢复库作为正式库（长期保留 `MYSQL_DATABASE` 指向它），或停机后按团队流程重建 `travel_agent` 并再恢复一次；旧库至少保留一个备份周期。

> `mysqldump` 默认不带 `CREATE DATABASE`（未使用 `--databases`），因此恢复到指定库时不会覆盖库名；这是有意为之。

## 4. Flyway 迁移前快照与最新版本（V24）之后重跑

### 4.1 迁移前快照（每次发布必做）

1. 按 §2 做一次完整逻辑备份，并记录 `flyway_schema_history` 的最大版本与行数。
2. 记录代码 commit 与镜像 tag。
3. 备份 `backend-data` 卷（照片/在线城市缓存），见 §5。
4. 运行 `docker compose config` 保存最终配置（脱敏后）作为发布记录附件。
5. 迁移后不得修改已发布脚本；任何 schema 变更都必须新增 `V{n}__描述.sql`。

### 4.2 当前迁移链与已知证据

| 项 | 事实 | 来源 |
| --- | --- | --- |
| 迁移文件 | 24 个版本 / 25 个脚本（V13 双方言两份；V8/V12 仅 MySQL；V14 仅 H2） | [数据模型](../guide/data-model.md) §6 / §9 |
| MySQL 实际应用 | V1–V13 + V15–V24（**没有 V14**，H2 专属） | `application-mysql.yml` 的 `locations` |
| 真实 MySQL 8.4 证据 | 仓库内已有 MySQL 8.4 的迁移证据（[MySQL 迁移验证](../guide/mysql-migration.md) §5 记录到 V19；[路线图](../PROJECT_ROADMAP.md) 2026-11-22 记为 V1–V22） | 历史证据，非本次演练 |
| V23 | 已合入；**2026-11-23 在真实 MySQL 8.4.10 上复跑通过**（当时 22 条迁移、`validate` 通过；加入 V24 后全量 23 条见下一行） | 本地演练证据 |
| V24 | **2026-11-23 已在真实 MySQL 8.4.10 验证**：`mysql-it` 全量 23 条 success；演练库由 V23 → V24 升级 1 条（35 ms）后，备份/恢复/校验全部通过 | 本地演练证据 |
| 回滚脚本 | **没有**（forward-only） | 迁移目录内无 `U*.sql` / `undo` |

### 4.3 最新迁移（V24）之后重跑迁移（发布前必做）

用仓库自带的真实 MySQL 测试重跑一次（测试会创建一次性库，不碰生产库）：

```powershell
# 仓库根目录；需要本机/内网 MySQL 8.4 实例与具备 CREATE DATABASE 权限的账号
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mysql-it.ps1 -DbPort 3306 -DbPassword <口令>
```

或手动指定环境变量后执行 Maven 测试（`MYSQL_IT=true` 大小写敏感；测试会建 `travel_agent_it_<ts>` 一次性库并保留为证据）：

```bash
cd travel-agent-server
MYSQL_IT=true MYSQL_IT_HOST=127.0.0.1 MYSQL_IT_PORT=3306 \
MYSQL_IT_USERNAME=root MYSQL_IT_PASSWORD=<口令> \
mvn -B -Dtest=MySqlFlywayMigrationTest test
```

验收：`Tests run: 1, Failures: 0`、`flyway_schema_history` 全 `success=1`、`ddl-auto=validate` 通过。执行后把日期、版本、输出摘要补进 §7 的演练记录；**未执行前本文不声称 V24 已在 MySQL 8.4 通过**。

## 5. 照片 / S3 对象备份

### 5.1 本地照片卷（`PHOTOS_STORE=local`）

```bash
# 备份：先停后端，避免写入竞争
docker compose stop backend
docker run --rm \
  -v travel-agent_backend-data:/data:ro \
  -v "$PWD/backups":/backup \
  alpine tar czf /backup/backend-data-$(date +%Y%m%d).tar.gz -C /data .
docker compose start backend
```

恢复：停后端 → 清空卷内容 → 解包 → 启动后端；H2 模式下还要删除残留 `travel-agent.lock.db`。

### 5.2 S3 兼容对象存储（`PHOTOS_STORE=s3`）

- 开启 bucket **版本控制**与生命周期（保留策略由运营确认），必要时跨区复制。
- 至少做一次“清单对齐”：`trip_photo.stored_name` 集合与 bucket 对象键集合一致。
- 对象键是扁平 UUID（无用户/行程前缀），**不能按前缀判断归属**；恢复时只能按清单逐对象核对。
- 凭证轮换与最小权限见 [生产配置清单](production-config.md) §3。

### 5.3 一致性顺序

照片与数据库必须来自同一时间点：先 dump MySQL，再备份照片会导致“有行无文件/有文件无行”。演练时记录两者的时间差，超过可接受窗口就重做备份。

### 5.4 Redis 与容器日志（不纳入关键备份）

- **Redis 不是业务数据**：`redis-data` 只存限流计数（固定窗口，TTL 数分钟）。丢失只会让计数清零、短期限流额度重置；不纳入 RTO/RPO 的关键数据清单，也不影响行程/照片/账号。
- 运行期 Redis 不可用时应用是 **fail-open**（放行并告警）；但 compose 的 `depends_on: redis: service_healthy` 意味着 **Redis 容器不健康时 backend 不会启动**，这属于启动期依赖，恢复顺序上应先让 Redis healthy。
- 如果确实想保留窗口计数（通常没必要），可在停 backend 后复制 `redis-data` 卷；AOF 只是让 Redis 重启后恢复自身计数，不等于业务备份。
- **容器日志**：`backend` / `web` 已配 `json-file` 10m × 3 轮转，日志不会无限增长，也**不要**把日志打进业务备份包（体积大、含运行细节）。需要事故取证时，在轮转覆盖前单独复制 `docker compose logs` 输出。

## 6. 校验步骤（恢复后必须全绿）

| # | 检查 | 命令 / 方法 | 期望 |
| --- | --- | --- | --- |
| 1 | 备份文件完整性 | `sha256sum -c <备份>.sha256` | OK |
| 2 | MySQL 版本 | `SELECT @@version;` | 8.4.x |
| 3 | Flyway 行数 | `SELECT COUNT(*), MAX(version) FROM flyway_schema_history WHERE success=1;` | 与应用版本一致，无 `success=0` |
| 4 | 失败迁移 | `SELECT COUNT(*) FROM flyway_schema_history WHERE success=0;` | 0 |
| 5 | 关键表行数 | 见下方 SQL | 与备份前一致 |
| 6 | 表总数 | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();` | 26（21 实体 + 6 退役表，见数据模型；旧库可能不同） |
| 7 | 照片对象数 | `SELECT COUNT(*) FROM trip_photo;` 与存储对象数对比 | 一致（允许已知孤儿，需记录） |
| 8 | 记账恒等式抽样 | `SELECT SUM(amount) FROM expense WHERE user_id=?;` | 与业务报表口径一致 |
| 9 | 应用启动 | `docker compose restart backend`，看日志 | 启动成功、无 `Schema-validation` / `Migration checksum mismatch` |
| 10 | 业务冒烟 | 登录 → 行程列表 → 行程详情 → 助手发一条消息 | 通过 |

关键表行数 SQL：

```sql
SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'trip', COUNT(*) FROM trip
UNION ALL SELECT 'itinerary_item', COUNT(*) FROM itinerary_item
UNION ALL SELECT 'trip_photo', COUNT(*) FROM trip_photo
UNION ALL SELECT 'expense', COUNT(*) FROM expense
UNION ALL SELECT 'schedule_item', COUNT(*) FROM schedule_item
UNION ALL SELECT 'memo', COUNT(*) FROM memo
UNION ALL SELECT 'conversation_message', COUNT(*) FROM conversation_message
UNION ALL SELECT 'pending_plan', COUNT(*) FROM pending_plan
UNION ALL SELECT 'agent_run', COUNT(*) FROM agent_run
UNION ALL SELECT 'rag_document', COUNT(*) FROM rag_document;
```

> 行数只是粗校验：同一行数也可能内容损坏。关键表建议再抽样比对主键/更新时间，或对 `flyway_schema_history` 的 checksum 做核对。

## 7. 演练脚本与 RTO/RPO 记录模板

### 7.1 `scripts/backup-drill.ps1`

```powershell
# 只做备份（默认，不碰任何库结构）；PowerShell 7 可把 powershell 换成 pwsh
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-drill.ps1 `
  -Host 127.0.0.1 -Port 3306 -User root -Password <口令> `
  -Database travel_agent -OutDir .\backups\drill

# 备份 + 恢复到临时库 + 关键表计数对比（不 DROP 原库；临时库默认保留）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-drill.ps1 `
  -Host 127.0.0.1 -Port 3306 -User root -Password <口令> `
  -Database travel_agent -OutDir .\backups\drill -RestoreDb travel_agent_drill_20261122
```

- 无 `mysql` / `mysqldump` 客户端时脚本以**非 0 退出**并给出明确错误，不会产生“假 PASS”（本机未装客户端时实测退出码 2）。
- 密码通过临时 `defaults-extra-file` 传递，**不写命令行、不写日志**；脚本结束删除临时文件。
- 默认**不删除**临时库（避免破坏性操作）；确认后手工 `DROP DATABASE`，或加 `-CleanupRestoreDb` 让脚本在 PASS/FAIL 后清理。
- 输出 PASS/FAIL、备份/恢复耗时、每张关键表两侧行数、Flyway 行数与最大版本；报告可另存。
- 行数校验走合批查询（21 张表 2 次 `mysql` 调用，约 4 s）；2026-11-23 首跑用逐表查询时为 63 次调用、约 70 s，已改掉。

### 7.2 RTO / RPO 记录模板（每次演练复制一行填写）

| 日期 | 环境（MySQL 版本/数据量） | 备份耗时 | 恢复耗时 | 实测 RTO | 可接受 RPO | 校验结果 | 执行人 / 复核人 | 证据路径 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-11-23 | MySQL 8.4.10 / 48.8 MB（28 表；关键表 142,200 行：users 200 / trip 2,000 / itinerary_item 60,000 / conversation_message 50,000 / tool_call_trace 30,000） | 1.02 s（dump 27.8 MiB） | 5.06 s | **≈18 s**（恢复 5.06 s + 应用启动并 `validate` 12.75 s；含行数校验约 22 s） | 未定（每日全量最坏 24 h；dump 仅秒级，可提高频率） | 21 张关键表行数全等；Flyway 23/23 success（maxVersion=24，含 V24 升级路径）；应用 `validate` 通过 | lead（AI 执行）/ 待人工复核 | `.evidence/backup-drill/report-20261123-v24.json`、`app-start-v24.log`、`upgrade-v24.log`（本机、不入库） | **PASS（单机合成数据，生产需重演）** |
| `[待演练]` | `[8.4.x / xx MB]` | `[ ]` | `[ ]` | `[ ]` | `[ ]` | `[ ]` | `[ ]` | `[ ]` | `[ ]` |

> 目前**没有**已确认的 RTO/RPO 目标值。关键数据只有 **MySQL `travel_agent` + 照片对象**；Redis 限流计数与容器日志不纳入 RTO/RPO。2026-11-23 单机实测：48.8 MB 库恢复 5.06 s、应用启动 12.75 s（V24 架构）；RTO 随数据量/磁盘增长，生产类环境必须重演。每日一次全量备份意味着最坏 RPO 约 24 小时（按频率推算，未由业务确认）。目标值必须由业务确认后写进本表，并与隐私政策/对客承诺一致。

## 8. 回滚边界

| 场景 | 可否回滚 | 做法 |
| --- | --- | --- |
| 应用代码问题、无破坏性迁移 | 可 | 回退镜像 tag（`docker compose up -d --no-deps` 指定旧镜像），数据库不动 |
| 新增表/可空列 | 可（通常） | 旧版本代码忽略新结构；必须确认 `ddl-auto=validate` 不报错 |
| 删列/改类型/数据回填错误 | **不可靠** | Flyway 无 down；只能恢复发布前备份，期间数据丢失 |
| 迁移执行到一半失败 | 视情况 | 先确认无半完成 DDL，再走 repair/恢复（见 [部署与运维 §6.3.3](../guide/deployment.md)） |
| 数据库损坏/误删 | 可 | 恢复到临时库 → 校验 → 切换（§3） |

2026-11-23 演练结论（[证据](../review/2026-11-23-monitoring-and-rollback-drill.md) §2）：

- V24 这类**加列**迁移：回退到 N-1 jar 后，Flyway 把 V24 视为 future（`Successfully validated 23 migrations`），Hibernate `validate` 忽略多余列，注册/建行程/列表冒烟全 200；**只回退代码，不恢复数据库，RTO ≈15 s**。
- 删列/改类型/回填错误的破坏性迁移仍必须恢复发布前备份（RTO 取 §7.2 的 ≈18 s + 丢数窗口）。
- 回滚后若再次前滚到 V24，N-1 期间签发的 token 会失效一次，需在发布公告里写明。

纪律：

- **不要**为了回滚去改已发布迁移或删 `flyway_schema_history`。
- 回滚前先备份当前库；回滚后要再跑一次 §6 校验。
- 破坏性迁移的发布必须与备份演练同批完成，并在发布记录中写明“回滚 = 恢复备份”。

## 9. 演练检查清单（谁 / 何时 / 证据）

### 9.1 发布前（每次含迁移的发布）

- [ ] 执行人：`[姓名]`；复核人：`[姓名]`；时间：`[ ]`。
- [ ] 已做迁移前完整备份 + sha256 + 元数据（§2、§4.1）。
- [ ] 已在真实 MySQL 8.4 复跑 `MySqlFlywayMigrationTest`（V24 及以后所有增量），证据：`[命令输出/CI 链接]`。
- [ ] 已恢复到临时库并跑完 §6 的 1–8 项；证据：`[报告路径]`。
- [ ] 照片/对象存储与数据库时间点一致（§5.3）。
- [ ] 回滚方式已写明（应用回退 / 恢复备份），并在预发验证过。

### 9.2 定期演练（建议每季度，或换机/升级 MySQL 前）

- [ ] 完整备份 → 恢复 → 校验 → 应用启动 → 业务冒烟，全程计时。
- [ ] 填写 §7.2 一行 RTO/RPO 实测值。
- [ ] 保存证据：备份 sha256、恢复日志、行数对比、启动日志、冒烟截图。
- [ ] 复盘：恢复慢在哪、备份是否可读、权限是否足够、脚本是否需要改。
- [ ] 把结论写回本文（新增一行记录），不要只留在聊天记录里。

## 10. 未验证与风险

| 项 | 状态 |
| --- | --- |
| 真实生产类 MySQL 的备份→恢复→校验全过程 | **单机演练 1 次（2026-11-23）**；生产类环境（真实数据量/磁盘/网络）**未执行** |
| V23/V24 在真实 MySQL 8.4 复跑 | **已完成**（2026-11-23，全量 23 条迁移 + `validate`；V23→V24 增量升级 35 ms，见 §7.2） |
| RTO / RPO 实测 | 有一次单机值（RTO ≈16 s / 48.8 MB）；**目标值未定**，RPO 未由业务确认 |
| S3 对象备份/恢复 | **未验证** |
| 备份加密、异地、保留策略 | **未落地**（运维需配置并记录） |
| 定时备份任务 | **仓库未提供**（部署文档有 cron 示例，需按环境落地） |

相关文档：[部署与运维](../guide/deployment.md)（卷、备份命令、迁移）· [MySQL 迁移验证](../guide/mysql-migration.md) · [数据模型](../guide/data-model.md)（迁移链与表清单）· [生产配置清单](production-config.md) · [故障处理手册](runbook.md) · [数据删除流程](../legal/data-deletion.md)