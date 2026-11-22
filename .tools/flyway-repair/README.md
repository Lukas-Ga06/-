# flyway-repair —— 独立 Flyway 运维小工具（batch10）

**不属于 Spring Boot 应用，不参与 mvn 构建**（在 `src/main` 之外）。用途：在应用之外执行
`info / validate / repair / migrate`，典型场景是「已发布迁移脚本修了语法错误（只改注释/空白、
语义不变）后，已应用过它的库出现 `Migration checksum mismatch`」。

## 背景（为什么需要它）

batch10 修掉了 `V4__rag_fingerprint.sql` 第 4 行的 `--（` 注释：MySQL 要求 `--` 后必须有
空白/控制字符，否则这一行不是注释而是 SQL 语句的一部分 —— 于是**任何全新 MySQL 库**都会在
V4 报 `SQLSyntaxErrorException`（H2 能容忍，所以本地 H2 一直没暴露）。

修完 V4 后，**已经应用过旧 V4 的库**（本地 H2 开发库、现网库）checksum 对不上，应用启动会报：

```
Migration checksum mismatch for migration version 4
-> Applied to database : 826714073
-> Resolved locally    : -231615481
Either revert the changes to the migration, or run repair to update the schema history.
```

`repair` 只把 `flyway_schema_history` 里记录的 checksum/状态对齐到当前脚本：
**不执行任何 DDL、不改业务数据、不重跑迁移**。

## 用法

```powershell
# 1) 先看状态（只读）
powershell -NoProfile -ExecutionPolicy Bypass `
  -File D:\travel-agent\.tools\flyway-repair\run.ps1 -Command info `
  -JdbcUrl "jdbc:mysql://127.0.0.1:3306/travel_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" `
  -DbUser root -DbPassword 123456 `
  -Locations "filesystem:D:/travel-agent/travel-agent-server/src/main/resources/db/migration,filesystem:D:/travel-agent/travel-agent-server/src/main/resources/db/migration-mysql"

# 2) 对齐 checksum（唯一会写库的操作，只写 flyway_schema_history）
#    把 -Command info 换成 -Command repair 再跑一次

# 3) 校验（repair 后应为 [OK] validate passed）
#    -Command validate

# 4) 也可以在应用外把某个库迁到指定版本（例：只跑到 V7）
#    -Command migrate -Target 7
```

- H2 库**不要传 `-DbPassword`**（脚本默认空密码）；PowerShell 会把裸 `-` 当参数前缀，`-DbPassword -` 会 rc=2 且零输出（例：本地开发库
  `jdbc:h2:file:D:/travel-agent/travel-agent-server/data/travel-agent;MODE=MySQL`，用户 `sa`）。
- `-Locations` 用逗号分隔，**与对应 profile 的 `spring.flyway.locations` 保持一致**：
  H2 = `db/migration` + `db/migration-h2`；MySQL = `db/migration` + `db/migration-mysql`
  （两个方言目录里各有一个 V13，Flyway 只对同一 locations 内重复版本报错）。
- jar 版本与应用的依赖树一致（flyway-core / flyway-mysql 11.7.2、mysql-connector-j 9.4.0、
  h2 2.3.232），从本机 `~/.m2/repository` 取；缺 jar 会明确报 `missing jar`。

## 本地开发库也要跑一次

`travel-agent-server/data/travel-agent.mv.db`（gitignore 的本地 H2 库）里 V4 是旧 checksum，
升级代码后第一次启动会报上面的 mismatch。跑一次：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File D:\travel-agent\.tools\flyway-repair\run.ps1 -Command repair `
  -JdbcUrl "jdbc:h2:file:D:/travel-agent/travel-agent-server/data/travel-agent;MODE=MySQL" `
  -DbUser sa `
  -Locations "filesystem:D:/travel-agent/travel-agent-server/src/main/resources/db/migration,filesystem:D:/travel-agent/travel-agent-server/src/main/resources/db/migration-h2"
```

现网 MySQL 库同理（加上 `db/migration-mysql` 目录），**每个已应用过旧 V4 的库都要跑一次**，
之后启动即恢复正常。删库重建的库不需要 repair。

## 已验证的演练（2026-11-16，本机）

见 batch10 交付说明「checksum mismatch → repair → 成功」三段原始输出；结论：
旧 V4 库启动失败（明确报 mismatch）→ repair → 再次启动成功。