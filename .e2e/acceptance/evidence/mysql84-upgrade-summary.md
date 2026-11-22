# MySQL 8.4 旧库升级 + 三态重启 正式回归
- 开始时间：2026-11-19 16:29:41
- worktree：D:\travel-agent\.worktrees\mysql-upgrade
- 便携 MySQL：D:\travel-agent\.tools\mysql84\mysql-8.4.10-winx64
- 证据目录：D:\travel-agent\.worktrees\mysql-upgrade\.e2e\acceptance\evidence
- 本机：Windows 10.0.26300.0，无 Docker（原生 mysqld，不涉及 docker compose）

## 0. 预检（端口隔离 / 残留清理 / 构建）   (16:29:41)
- 3306 既有 MySQL 监听 PID：7920
  [PASS] E1:3306 既有 MySQL 服务在监听 — pid=7920
- 预检时 3307/3317 是否在监听：False / False（本脚本不会使用它们）
- 复用已构建的当前版本 jar：D:\travel-agent\.worktrees\mysql-upgrade\travel-agent-server\target\travel-agent-server-0.1.0.jar
- 从 git 归档旧版本提交 9918a4f 的源码（模拟旧部署，不改动主仓/当前 worktree 的 src）...
- 构建旧版本 jar（提交 9918a4f，其最高迁移=V16）...
- 旧版本 jar 构建完成：D:\travel-agent\.worktrees\mysql-upgrade\.e2e\acceptance\.tmp\mysql-upgrade\old-src\travel-agent-server\target\travel-agent-server-0.1.0.jar
- 实例端口：MySQL=3318，后端=18094（首选 3318/18094，占用则顺延）
  [PASS] E2:端口与既有 MySQL 隔离 — dbPort=3318
  [PASS] E3:后端端口非 8080/既有服务 — backendPort=18094

## 1. 初始化全新 MySQL 8.4 实例（独立 datadir）   (16:30:27)
- mysqld --initialize-insecure 结束（exit=0），datadir 系统表存在=True
  [PASS] M1:全新 datadir 初始化成功 — exit=0 systemTables=True
- mysqld 已就绪（pid=27524）
- SELECT VERSION(): 8.4.10
- @@port=3318  @@datadir=D:\\travel-agent\\.worktrees\\mysql-upgrade\\.e2e\\acceptance\\.tmp\\mysql-upgrade\\data\\
  [PASS] M2:MySQL 版本=8.4.10 — VERSION()=8.4.10
  [PASS] M3:实例端口正确 — port=3318
  [PASS] M4:独立 datadir — datadir=D:/travel-agent/.worktrees/mysql-upgrade/.e2e/acceptance/.tmp/mysql-upgrade/data/
  [PASS] M5:迁移前是空库 — tables=0

## 2a. 旧部署探针：spring.flyway.target=15（验证 V15 能否作为可运行旧基线）   (16:30:35)
- target=15 启动进程已退出=True
    2026-11-19T16:30:40.383+08:00  WARN [] 27564 --- [travel-agent-server] [           main] o.f.c.internal.database.base.Database    : Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.
    2026-11-19T16:30:40.594+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "1 - baseline"
    2026-11-19T16:30:41.396+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "2 - conversation message"
    2026-11-19T16:30:41.502+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "3 - llm quota"
    2026-11-19T16:30:41.546+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "4 - rag fingerprint"
    2026-11-19T16:30:41.570+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "5 - rag document user"
    2026-11-19T16:30:41.591+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "6 - pending plan status"
    2026-11-19T16:30:41.637+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "7 - trip version"
    2026-11-19T16:30:41.654+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "8 - llm quota unique"
    2026-11-19T16:30:41.689+08:00  INFO [] 27564 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "9 - list indexes"
  [PASS] O1:target=15 时库停在 V15 — history=14|15|0（MySQL 无 V14，V14 仅 H2；V1..V13+V15=14 条）
  [PASS] O2:V15 不能作为可运行旧基线（校验缺列，预期） — 启动失败且报告 Schema-validation 缺列（V16 实体列，如 pending_plan.content_hash）

## 2b. 旧部署模拟：旧版发布代码（提交 9918a4f，最高迁移=V16）+ target=16   (16:30:45)
- 后端 [old-v16] 健康，耗时 12.4s（pid=4620）
- 旧版后端启动耗时 12.4s
    2026-11-19T16:30:54.362+08:00  WARN [] 4620 --- [travel-agent-server] [           main] o.f.c.internal.database.base.Database    : Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.
    2026-11-19T16:30:54.441+08:00  INFO [] 4620 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "16 - plan execution"
    2026-11-19T16:30:54.734+08:00  INFO [] 4620 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Successfully applied 1 migration to schema `travel_agent`, now at version v16 (execution time 00:00.271s)
    2026-11-19T16:30:59.724+08:00  INFO [] 4620 --- [travel-agent-server] [           main] c.travel.agent.TravelAgentApplication    : Started TravelAgentApplication in 9.871 seconds (process running for 10.347)
- V16 历史：15|16|0；表数=25；重复版本行=0
  [PASS] F1:旧版后端启动成功 — target=16，耗时 12.4s
  [PASS] F2:迁移停在 V16 — history=15|16|0（V1..V13+V15+V16=15 条）
  [PASS] F3:旧 schema 无重复版本行 — duplicates=0
  [PASS] F4:ddl-auto=validate 通过（无 Schema-validation） — 旧实体与 V16 schema 一致
  [PASS] F5:MySQL 8.4 仅 WARN（Flyway 支持到 8.1） — 仅 WARN，迁移与 validate 通过：2026-11-19T16:30:54.362+08:00  WARN [] 4620 --- [travel-agent-server] [           main] o.f.c.internal.database.base.Database    : Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.
  [PASS] F6:旧 schema 确实缺后续迁移的列 — trip.party_size=0 agent_task.depends_on=0
  [PASS] F7:V16 表数量已记录 — tables=25

## 3. 通过真实 HTTP API 灌入业务数据（旧 schema V16）   (16:31:02)
  [PASS] D1:登录成功（demo/demo123） — tokenLen=150
  [PASS] D2:HTTP 建行程成功 — tripId=1 destination=苏州 budget=8000 status=PLANNING
  [PASS] D3:HTTP 写行程条目成功 — itemIds=1,2,3
  [PASS] D4:HTTP 记支出成功（预算口径） — amounts=128.50/90.00
  [PASS] D5:HTTP 写备忘/日程成功 — memos=2 schedule=1
- 规划：status=NEED_CONFIRMATION planId=62e88ba6-bec0-40de-8d73-27909cd33e91 contentHash=2dffb5cfb41144751a42e1c11ed2f0a4492d5c090d2282cf570115cc71968c97 changes=11
  [PASS] D6:HTTP 触发规划 run 并待确认 — status=NEED_CONFIRMATION planId=62e88ba6-bec0-40de-8d73-27909cd33e91 hashLen=64
  [PASS] D7:确认落库生成行程与执行台账 — confirmStatus=COMPLETED tripId=2 executionId=ea5f3937-3cfd-41cd-a68e-5a8c36521b47
  [PASS] D8:run 已落库且可回查 — runs=1 runId=d193ba9b-d4ce-495e-906e-06186e1e74f4
  [PASS] D9:旧 run 的任务链/工具 trace 已落库 — tasks=5 toolCalls=16
  [PASS] D10:pending_plan 有旧行 — pendingPlanRows=1 chat2Status=NEED_CONFIRMATION
  [PASS] D11:直接 SQL 补写覆盖后续迁移列 — tripId=3 agentTask=sql-seed-old-task（挂在旧 run 下，故该 run 任务数 5→6）
  [PASS] D12:关键表齐全 — tables=24 missing=
  [PASS] D13:关键表均有真实数据 — trip=3 itinerary_item=14 expense=2 memo=2 schedule_item=3 pending_plan=1 plan_execution=1 agent_run=2 agent_task=10 tool_call_trace=32 users=1
- 升级前快照：24 张表；关键表行数：trip=3 itinerary_item=14 expense=2 memo=2 schedule_item=3 pending_plan=1 plan_execution=1 agent_run=2 agent_task=10 tool_call_trace=32 users=1
- 升级前关键字段快照已保存（pre-upgrade keyFields：trips=3 items=14 runs=2 tasks=10）

## 4. 旧库增量升级：停旧后端 → 最新代码（target=latest）启动   (16:31:07)
  [PASS] U0:旧后端已停止 — backendPort=18094 closed
- 后端 [new-v18] 健康，耗时 10.4s（pid=45892）
    2026-11-19T16:31:22.508+08:00  WARN [] 45892 --- [travel-agent-server] [           main] o.f.c.internal.database.base.Database    : Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.
    2026-11-19T16:31:22.585+08:00  INFO [] 45892 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "17 - trip party size"
    2026-11-19T16:31:22.639+08:00  INFO [] 45892 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema `travel_agent` to version "18 - agent task dag"
    2026-11-19T16:31:22.718+08:00  INFO [] 45892 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMigrate      : Successfully applied 2 migrations to schema `travel_agent`, now at version v18 (execution time 00:00.090s)
    2026-11-19T16:31:27.678+08:00  INFO [] 45892 --- [travel-agent-server] [           main] c.travel.agent.TravelAgentApplication    : Started TravelAgentApplication in 9.41 seconds (process running for 9.908)
- V18 历史：17|18|0；表数=25；重复版本行=0
  [PASS] U1:最新代码启动成功且 validate 通过 — 启动 10.4s
  [PASS] U2:增量迁移只应用 V17/V18 — migrated="17 - trip party size","18 - agent task dag"
  [PASS] U3:迁移到 V18 且全部成功 — history=17|18|0
  [PASS] U4:flyway_schema_history 无重复版本行 — duplicates=0
  [PASS] U5:升级未新增业务表（只加列） — tablesBefore=25 tablesAfter=25
  [PASS] U6:后续迁移的新列已建成 — trip.party_size=1 agent_task(depends_on,attempt,replaces_task_id)=3
  [PASS] U7:旧迁移未重跑（V1..V16 历史摘要与升级前一致） — pre=15|585ba0fa5eaa933448392ac58fca8ff0 post=15|585ba0fa5eaa933448392ac58fca8ff0 oldRows=15
  [PASS] U8-users:行数与旧列内容哈希一致 — table=users rows=1->1 hash=f0a993a571c11a9cf5053af57826ce16->f0a993a571c11a9cf5053af57826ce16
  [PASS] U8-trip:行数与旧列内容哈希一致 — table=trip rows=3->3 hash=a018d15ae99588e7b3b87029d4997ea0->a018d15ae99588e7b3b87029d4997ea0
  [PASS] U8-itinerary_item:行数与旧列内容哈希一致 — table=itinerary_item rows=14->14 hash=d136fac6ac3be0af0a41b069f0f5fb1c->d136fac6ac3be0af0a41b069f0f5fb1c
  [PASS] U8-expense:行数与旧列内容哈希一致 — table=expense rows=2->2 hash=0c19deea5345935e36820f8d686bdf81->0c19deea5345935e36820f8d686bdf81
  [PASS] U8-memo:行数与旧列内容哈希一致 — table=memo rows=2->2 hash=eb04fc604c0fb709939871fa6aa5b08f->eb04fc604c0fb709939871fa6aa5b08f
  [PASS] U8-schedule_item:行数与旧列内容哈希一致 — table=schedule_item rows=3->3 hash=ba7ffc0a2db2881ab4db1aa7dc3e4059->ba7ffc0a2db2881ab4db1aa7dc3e4059
  [PASS] U8-pending_plan:行数与旧列内容哈希一致 — table=pending_plan rows=1->1 hash=419074e3337aa1042b0873bbe58b97a1->419074e3337aa1042b0873bbe58b97a1
  [PASS] U8-plan_execution:行数与旧列内容哈希一致 — table=plan_execution rows=1->1 hash=306741f57d3259d27e3c6e2ee62ff314->306741f57d3259d27e3c6e2ee62ff314
  [PASS] U8-agent_run:行数与旧列内容哈希一致 — table=agent_run rows=2->2 hash=2327c41b586cbd73883f266b3bd12761->2327c41b586cbd73883f266b3bd12761
  [PASS] U8-agent_task:行数与旧列内容哈希一致 — table=agent_task rows=10->10 hash=3cb24f2c04c5748e424de49d4dcd77b5->3cb24f2c04c5748e424de49d4dcd77b5
  [PASS] U8-tool_call_trace:行数与旧列内容哈希一致 — table=tool_call_trace rows=32->32 hash=020d8ba77eee9c57149ee30947d0d94a->020d8ba77eee9c57149ee30947d0d94a
  [PASS] U9:全部表逐表行数/内容一致 — tables=24 mismatches=
  [PASS] U10:关键字段快照未变（HTTP 数据/旧列原值） — trips=3 items=14 runs=2 tasks=10
  [PASS] U11:trip.party_size 对旧行回填=1 — badRows=0
  [PASS] U12:agent_task DAG 列默认值正确 — attemptBad=0 dagNonNull=0
  [PASS] U13:升级后 HTTP 行程列表与旧库一致 — pre=1,2,3 post=1,2,3
  [PASS] U14:升级后旧行程条目可读且数量一致 — pre=3 post=3
  [PASS] U15:升级后旧 runId 仍可查 — HTTP=200 tasks=6（DB 旧 run 任务数=6，含 D11 SQL 种子 1 条）
  [PASS] U16:升级后预算接口可读旧数据 — budget=8000.00 planned=898.50 actual=218.50

## 5.1 仅重启后端（MySQL 不动）   (16:31:32)
- 后端 [restart-backend] 健康，耗时 10.4s（pid=13072）
  [PASS] R1:仅重启后端后健康 — 耗时 10.4s
  [PASS] R2:仅重启后端后旧 runId 可查 — HTTP=200
  [PASS] R3:仅重启后端后迁移未重跑 — history=17|f4f7a0b64d2a41a132c82f6e615aa83b
  [PASS] R4:仅重启后端后旧数据一致 — counts=trip=3|item=14|expense=2|memo=2|schedule=3|pending=1|exec=1|run=2|task=10|tool=32|user=1
  [PASS] R5:仅重启后端后可写新数据 — HTTP=200 memoId=3

## 5.2 仅重启 MySQL（后端进程不动，验证连接池恢复）   (16:31:47)
  [PASS] R6:MySQL 已停止 — port=3318 closed
  [PASS] R7:MySQL 重启后后端连接池自动恢复 — 耗时 0s
  [PASS] R8:MySQL 重启后旧 runId 可查 — HTTP=200
  [PASS] R9:MySQL 重启后迁移未重跑 — history=17|f4f7a0b64d2a41a132c82f6e615aa83b
  [PASS] R10:MySQL 重启后数据一致 — counts=trip=3|item=14|expense=2|memo=3|schedule=3|pending=1|exec=1|run=2|task=10|tool=32|user=1
  [PASS] R11:MySQL 重启后可写新数据 — HTTP=200 memoId=4

## 5.3 整栈重启（先停后端，再停 MySQL；反向启动）   (16:31:54)
  [PASS] R12:整栈已完全停止 — backend/mysql ports closed
- 后端 [restart-full] 健康，耗时 10.4s（pid=13108）
  [PASS] R13:整栈重启后后端健康 — 耗时 10.4s
  [PASS] R14:整栈重启后旧 runId/旧行程可查 — runHTTP=200 trips=1,2,3
  [PASS] R15:整栈重启后迁移未重跑 — history=17|f4f7a0b64d2a41a132c82f6e615aa83b
  [PASS] R16:整栈重启后数据一致 — counts=trip=3|item=14|expense=2|memo=4|schedule=3|pending=1|exec=1|run=2|task=10|tool=32|user=1
  [PASS] R17:整栈重启后可写新数据 — HTTP=200 memoId=5

## 6. 清理与收尾   (16:32:23)
  [PASS] C1:后端端口已释放 — backendPort=18094
  [PASS] C2:MySQL 端口已释放 — dbPort=3318
  [PASS] C3:临时 datadir 已删除 — path=D:\travel-agent\.worktrees\mysql-upgrade\.e2e\acceptance\.tmp\mysql-upgrade\data
  [PASS] C4:无本脚本残留进程 — pids= dbPortOwners=
  [PASS] C5:3306 既有 MySQL 未被影响 — before=7920 after=7920
  [PASS] C6:3307/3317 未被本脚本使用/改动 — 3307=False(was False) 3317=False(was False)
- 清理完成（-KeepRunning 可保留现场；-KeepTemp 可保留 .tmp 构建缓存）

## 结论
- 断言：80 PASS / 0 FAIL（共 80）
- 总耗时：177.6s
  - 0. 预检（端口隔离 / 残留清理 / 构建）: 46.7s
  - 1. 初始化全新 MySQL 8.4 实例（独立 datadir）: 7.4s
  - 2a. 旧部署探针：spring.flyway.target=15（验证 V15 能否作为可运行旧基线）: 9.8s
  - 2b. 旧部署模拟：旧版发布代码（提交 9918a4f，最高迁移=V16）+ target=16: 16.8s
  - 3. 通过真实 HTTP API 灌入业务数据（旧 schema V16）: 5.4s
  - 4. 旧库增量升级：停旧后端 → 最新代码（target=latest）启动: 25s
  - 5.1 仅重启后端（MySQL 不动）: 15.1s
  - 5.2 仅重启 MySQL（后端进程不动，验证连接池恢复）: 7.5s
  - 5.3 整栈重启（先停后端，再停 MySQL；反向启动）: 28.5s
  - 6. 清理与收尾: 15.3s
- 判定：PASS

## 环境与已知约束
- Windows（无 Docker）：未运行、也未假装运行 docker compose；MySQL 为原生便携实例 8.4.10。
- 3306 既有 MySQL 8.0 监听 PID：7920（全程未占用/未改动）；3307/3317 未复用。
- Flyway 对 MySQL 8.4 仅告警（已捕获 WARN 行）：支持到 8.1，但迁移与 ddl-auto=validate 均通过。
- 旧部署基线：V15/V16 由同一提交（b8c9b89）引入，不存在「以 V15 为最新」的可构建发布；用旧版提交 9918a4f（其最高迁移=V16）模拟旧部署。
  target=15 的真实结果见 O1/O2：库确实停在 V15，但任何发布代码都无法通过 ddl-auto=validate（缺 pending_plan.content_hash 等 V16 列）。
- 升级增量：V17（trip.party_size）、V18（agent_task.depends_on/attempt/replaces_task_id），恰为「给已有表补 NOT NULL DEFAULT 列」的风险路径。