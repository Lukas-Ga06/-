# infra compose 的 mysql:8.4 冷启动与 Flyway 全量迁移（2026-11-24）

> 快照日期：2026-11-24 ｜ 验证范围：`docker-compose.infra.yml` 的 `mysql:8.4` 新卷冷启动，以及在该实例上跑通 `db/migration` + `db/migration-mysql` 全量迁移与 Hibernate `validate` ｜ 环境：Windows 11 / Docker Desktop 29.9.3（Linux engine，overlayfs）/ MySQL 8.4.13 / JDK 17 / Maven 3.9.16 ｜ 结论：**冷启动成功，23 条 MySQL 迁移全部应用（v24，8.09 s），`MySqlFlywayMigrationTest` 1 test / 0 failures；CHANGELOG 里「8.4 冷启动未验证」作废。** 旧 8.0 数据卷的就地升级未演练（本次用的是新卷）。

## 1. 为什么要单独验这一次

2026-11-24 把 `docker-compose.infra.yml` 的 `mysql:8.0` 改成 `mysql:8.4`（与生产 compose 同 tag），只做到了 `docker compose config` 级别的校验。当时本机 Docker daemon 未运行，CHANGELOG 因此留了一条「8.4 冷启动未验证」。

这条不是形式主义：8.0 → 8.4 有两个只有真正启动才会暴露的差异——**已有 8.0 数据卷首次用 8.4 启动会就地升级数据目录且不可回退**，以及 8.4 的默认 collation 变成 `utf8mb4_0900_ai_ci`。新卷冷启动能证明「文档写的流程在 8.4 上确实能跑通」，但**不能**证明旧卷升级安全，后者仍按 [部署文档 §6.2](../guide/deployment.md) 的「先备份或删卷重建」处置。

## 2. 环境与命令

| 项 | 取值 |
| --- | --- |
| Docker | Docker Desktop 29.9.3，引擎 Linux / overlayfs；`docker compose version` = v5.7.0 |
| 镜像 | `mysql:8.4`，容器内 `SELECT VERSION()` = **8.4.13** |
| 端口 | 宿主机 `3307` → 容器 `3306`（宿主机 3306 被本机 MySQL80 服务占用，用临时 override 换端口，**不改被验证的 compose 文件**） |
| 项目/卷 | 独立 project `travel-agent-infra`，卷 `travel-agent-infra_mysql-data`（全新，与完整栈的 `travel-agent_mysql-data` 隔离） |
| 后端 | JDK 17 + Maven 3.9.16，`MySqlFlywayMigrationTest`（`@SpringBootTest` + `mysql` profile，即 Flyway 迁移 + Hibernate `ddl-auto=validate`） |

```powershell
# 1) 冷启动（override 只做端口重映射；-p 保证卷是新的）
docker compose -p travel-agent-infra -f docker-compose.infra.yml `
  -f .evidence/docker-compose.infra.override.yml up -d mysql

# 2) 等 mysqladmin ping 通过后，确认版本与空库
docker exec travel-agent-mysql mysql -uroot -proot -e `
  "SELECT VERSION(); SHOW DATABASES LIKE 'travel_agent';"

# 3) 全量迁移 + Hibernate validate（一次性库由测试自建，不碰 travel_agent）
$env:MYSQL_IT='true'; $env:MYSQL_IT_PORT='3307'
$env:MYSQL_IT_USERNAME='root'; $env:MYSQL_IT_PASSWORD='root'
mvn -B -Dtest=MySqlFlywayMigrationTest test
```

## 3. 结果

| 检查 | 实测 |
| --- | --- |
| 冷启动 | 容器 `travel-agent-mysql` 创建 → 启动 → `mysqladmin ping` 通过；`SELECT VERSION()` = `8.4.13` |
| 空库 | `MYSQL_DATABASE=travel_agent` 已建出，`information_schema.tables` 计数 = 0（未挂 `sql/init.sql`，与 compose 注释一致） |
| Flyway | `Successfully applied 23 migrations to schema 'travel_agent_it_20261124220251', now at version v24 (execution time 00:08.094s)` |
| Hibernate validate | 同一 `@SpringBootTest` 上下文以 `ddl-auto=validate` 启动通过（21 个实体 / 21 张表逐项断言） |
| 测试结果 | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；`FLYWAY_TEST_EXIT=0` |

迁移版本集合为 **V1–V13 + V15–V24（共 23 条，无 V14）**：V14 是 H2 专属脚本，不在 MySQL 链路里，与 `MySqlFlywayMigrationTest` 的 `DOCUMENTED_MYSQL_VERSIONS` 注释一致。

## 4. 已知提示（不影响本次结论）

- Flyway 在 8.4 上打印 `MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.` 迁移结果正常，但**升级 Flyway 以消除 8.4 支持声明缺口**是一个独立的依赖升级项，不并入本批次。
- V9 报 `Duplicate index 'idx_schedule_item_user_sched_date' ... deprecated ... Error Code: 1831`：MySQL 对重复索引定义的弃用警告，最终表结构仍正确（索引断言通过）；属存量迁移脚本的历史写法，未在本批次改动。
- 日志在本机 `.evidence/infra-cold-start.log`（`.evidence/` 已 gitignore，不入库）。

## 5. 未覆盖

- **8.0 旧数据卷就地升级**：本次是全新卷；旧卷升级不可回退，处置见 [部署文档 §6.2](../guide/deployment.md)。
- **完整栈容器冷启动**：本快照只验 infra compose 的 MySQL；全栈起停见 [2026-11-23 冷启动快照](2026-11-23-container-cold-start-and-llm-load.md)。
- **collation 差异**：本地不设 collation，继承 8.4 默认 `utf8mb4_0900_ai_ci`；生产显式 `utf8mb4_unicode_ci`。只记录，不用本地口径反推生产 SQL。