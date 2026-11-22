# MySQL 8.4.10 native acceptance (2026-11-19 14:40:26)

## 0. 实例准备（独立 datadir / :3307）
- SELECT VERSION(): 8.4.10
- @@datadir: D:\\travel-agent\\.tools\\mysql8410-3307\\instance\\data\\
- @@basedir: D:\\travel-agent\\.tools\\mysql8410-3307\\mysql-8.4.10-winx64\\  @@port: 3307
  [PASS] M1:版本=8.4.10 — VERSION()=8.4.10
  [PASS] M2:端口=3307 — port=3307
  [PASS] M3:独立datadir — datadir=D:\\travel-agent\\.tools\\mysql8410-3307\\instance\\data\\
- 全新库表数（启动前）: 0
  [PASS] M4:全新库干净 — tables=0

## 1. 启动后端 → Flyway 迁移 + ddl-auto=validate
- 启动成功，耗时 16.6s（pid=35492）
- Flyway 日志关键行：
    2026-11-19T14:40:34.079+08:00  INFO [] 35492 --- [travel-agent-server] [           main] org.flywaydb.core.FlywayExecutor         : Database: jdbc:mysql://127.0.0.1:3307/travel_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true (MySQL 8.4)
    2026-11-19T14:40:34.148+08:00  WARN [] 35492 --- [travel-agent-server] [           main] o.f.c.internal.database.base.Database    : Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.
    2026-11-19T14:40:34.229+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Schema history table `travel_agent`.`flyway_schema_history` does not exist yet
    2026-11-19T14:40:34.242+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.core.internal.command.DbValidate     : Successfully validated 17 migrations (execution time 00:00.060s)
    2026-11-19T14:40:34.265+08:00  INFO [] 35492 --- [travel-agent-server] [           main] org.flywaydb.core.Flyway                 : All configured schemas are empty; baseline operation skipped. A baseline or migration script with a lower version than the baseline version may execute if available. Check the Schemas parameter if this is not intended.
    2026-11-19T14:40:34.270+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Creating Schema History table `travel_agent`.`flyway_schema_history` ...
    2026-11-19T14:40:34.402+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "1 - baseline"
    2026-11-19T14:40:35.203+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "2 - conversation message"
    2026-11-19T14:40:35.257+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "3 - llm quota"
    2026-11-19T14:40:35.309+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "4 - rag fingerprint"
    2026-11-19T14:40:35.332+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "5 - rag document user"
    2026-11-19T14:40:35.350+08:00  INFO [] 35492 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "6 - pending plan status"
  [PASS] F1:后端启动成功 — boot=16.6s
- flyway_schema_history: applied=17 latest=18 failed=0；业务表=25
  [PASS] F2:迁移全部成功 — applied=17 failed=0
  [PASS] F3:迁移到V18 — latest=18
  [PASS] F4:validate通过(启动即证明) — ddl-auto=validate 未报错
  [PASS] F5:业务表建成 — tables=25

## 2. 核心链路（登录 → 规划 → 确认 → 追踪 → 路线段）
- 登录: OK
- 规划: status=NEED_CONFIRMATION intent=PLAN_TRIP changes=15 tripId=
  [PASS] C1:规划进入待确认 — status=NEED_CONFIRMATION
  [PASS] C2:带内容指纹 — contentHash=3d1576b847246118994984479aa2e7c86c6c84d12d6cd253eeaf16bc2c6a91d1
- 确认: status=COMPLETED tripId=1 execution=0ed59d63-4f8d-45da-a3db-b1bee88bb946
- 三模块: calendar:SUCCEEDED,budget:SUCCEEDED,footprint:SUCCEEDED
  [PASS] C3:确认落库生成行程 — tripId=1
  [PASS] C4:三模块同步成功 — calendar:SUCCEEDED,budget:SUCCEEDED,footprint:SUCCEEDED
  [PASS] C5:run已落库 — runs=1 runId=949b3b7b-d573-424a-9752-6a0197daedc0
- 追踪: 任务链=INTENT → PLAN → PREVIEW → REVIEW → COMMIT；工具调用=23 次
  [PASS] C6:任务链完整 — chain=INTENT → PLAN → PREVIEW → REVIEW → COMMIT
  [PASS] C7:工具调用有trace — toolCalls=23
- 行程条目=15 路线段=10 sources=estimated
  [PASS] C8:行程可读 — itineraryItems=15
  [PASS] C9:路线段可读 — legs=10 sources=estimated
- system/status: routeProvider=estimated geoProvider=osm amapConfigured=False
  [PASS] C10:状态接口如实上报 — routeProvider=estimated
- 基线: trips=1 runs=1 db(trip/run/tool/expense)=1/1/23/0.00
  [PASS] C11:数据已入MySQL — db=1/1/23/0.00

### 3.1 仅重启后端（MySQL 不动）
- backend healthy after 14.4s (pid=3944)
- 重启后: trips=1 runs=1 db=1/1/23/0.00 flyway=17 failed=0 itineraryDb=15/3a166c6fa06957148a7b41e6a8f0cdc8
  [PASS] R1:后端重启后数据不丢 — tripIds=1 itinerarySame=True itDb=15/3a166c6fa06957148a7b41e6a8f0cdc8 (was 15/3a166c6fa06957148a7b41e6a8f0cdc8)
  [PASS] R2:重启未重跑迁移 — history=17 (was 17)
  [PASS] R3:后端重启后健康 — port=8080
  [PASS] R4:重启后写链路可用 — status=200/NEED_CONFIRMATION
- 新基线（重启后规划请求之后）: trips=1 runs=2 db=1/2/39/0.00

### 4.1 仅重启 MySQL（后端进程不动，验证连接池恢复）
  [PASS] R5:MySQL已停止 — port closed
- MySQL 重启后 ping: mysqld is alive
- 后端在 MySQL 重启后的恢复: True（耗时 3.1s）
  [PASS] R6:连接池自动恢复 — recovered=3.1s
- 重启后: db=1/2/39/0.00 memos=1 writeHTTP=200
  [PASS] R7:MySQL重启后数据一致 — db=1/2/39/0.00 baseline2=1/2/39/0.00
  [PASS] R8:MySQL重启后写链路可用 — memoHTTP=200 memos=1

### 5.1 整栈重启（先停后端，再停 MySQL；反向启动）
- 已全部停止（backend alive=False mysql alive=False）
- 整栈重启后端健康，耗时 12.6s
- 整栈重启后: trips=1 runs=2 db=1/2/39/0.00 flyway=17 failed=0 itinerary=15
  [PASS] R9:整栈重启数据不丢 — db=1/2/39/0.00 baseline2=1/2/39/0.00
  [PASS] R10:整栈重启未重跑迁移 — history=17 (was 17)

## 6. 结论
- 断言: 30 PASS / 0 FAIL（共 30）
- 判定: PASS