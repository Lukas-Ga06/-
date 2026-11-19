# 本地全新 MySQL 8.4 验收（Docker 环境不可用时的等价方案）
- MySQL 二进制: D:\travel-agent\.tools\mysql84\mysql-8.4.10-winx64
- 证据目录: D:\travel-agent\.evidence\mysql84-20261119-142900

## 1. 初始化全新 datadir
- mysqld --initialize-insecure 完成（datadir 为全新目录，系统表目录存在=True）
- mysqld 已监听 127.0.0.1:3317（pid=31032）
- SELECT VERSION(): 8.4.10
- 建库 travel_agent + 用户 travel（空库，等待 Flyway 迁移）
- 迁移前表数量: 0

## 2. 启动后端 + Flyway 迁移
- 后端健康，启动耗时 20.6s
- Flyway 关键日志：
    2026-11-19T14:29:15.705+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "1 - baseline"
    2026-11-19T14:29:16.736+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "2 - conversation message"
    2026-11-19T14:29:16.793+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "3 - llm quota"
    2026-11-19T14:29:16.849+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "4 - rag fingerprint"
    2026-11-19T14:29:16.884+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "5 - rag document user"
    2026-11-19T14:29:16.915+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "6 - pending plan status"
    2026-11-19T14:29:16.976+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "7 - trip version"
    2026-11-19T14:29:17.007+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "8 - llm quota unique"
    2026-11-19T14:29:17.058+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "9 - list indexes"
    2026-11-19T14:29:17.274+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "10 - rag document version"
    2026-11-19T14:29:17.538+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "11 - rag document key"
    2026-11-19T14:29:17.744+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "12 - rag active unique"
    2026-11-19T14:29:17.932+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "13 - baseline gap"
    2026-11-19T14:29:19.083+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "15 - agent trace"
    2026-11-19T14:29:19.409+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "16 - plan execution"
    2026-11-19T14:29:19.780+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "17 - trip party size"
    2026-11-19T14:29:19.973+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Migrating schema `travel_agent` to version "18 - agent task dag"
    2026-11-19T14:29:20.285+08:00  INFO [] 35436 --- [travel-agent-server] [           main] o.f.core.internal.command.DbMi
    grate      : Successfully applied 17 migrations to schema `travel_agent`, now at version v18 (execution time 00:04.228s
    )
- flyway_schema_history (applied / latest / failed): 17	9	0
- 迁移后表数量: 25

## 3. 核心链路（真实 HTTP + FR 断言）
== V2.2 验收 == base=http://127.0.0.1:18093 expectAmap=false expectLlm=false
[PASS] PRE 00.1 健康检查 UP :: UP
[PASS] FR-01 01.1 缺目的地/天数/人数 → 合并追问一次，不启动规划 :: missingSlots=["destination","days","partySize"] message=开始规划前，还需要确认：
· 去哪里？
· 玩几天？
· 几个人一起（儿童也算人数）？
预算和出发日期也可以顺手说；不说
[PASS] FR-01 01.2 短回答跨轮补槽（上海 → 天数 → 人数） :: 第二轮=["partySize"]
[PASS] FR-01 01.3 人数补齐后进入规划并明示兜底预算 :: tripId=1
[PASS] FR-01 01.4 人数落库：trip.partySize=2 :: destination=上海 partySize=2
[PASS] FR-08 08.1 方案生成带 planId + contentHash（SHA-256 64 位） :: planId=fe60b5c5-3d09-4c77-8e49-ce54f5c29243 changes=11
[PASS] FR-08 08.2 内容指纹不符 → 拒绝提交且方案不被消费 :: 方案内容与确认请求不一致（内容指纹校验未通过），请刷新后重新查看方案再确认。
[PASS] FR-08 08.3 正确指纹 → 提交成功 :: tripId=2 execution=f844e5d0-cdce-4fbe-8431-eb27196ba276
[PASS] FR-09 09.1 确认后三模块幂等同步全部 SUCCEEDED :: calendar:SUCCEEDED,budget:SUCCEEDED,footprint:SUCCEEDED
[PASS] FR-09 09.2 执行台账可回查且 planId 一致 :: executionId=f844e5d0-cdce-4fbe-8431-eb27196ba276
[PASS] FR-09 09.3 重复确认被拦截，只产生一份逻辑执行 :: 确认会话不存在或已过期，请重新发起请求。
[PASS] FR-06 06.1 预算装不下 → 审核 blocked + 要求显式接受风险 :: attempts=1 issues=BUDGET_OVERRUN
[PASS] FR-06 06.2 未勾选风险 → 拒绝提交且方案仍可取回 :: 该方案仍有未解决的高风险问题（预计花费 ¥1776 已超出预算 ¥100，可让我再精简一下），请在确认弹窗中勾选「我已了解风险」后再提交，或调整需求重新规划。
[PASS] FR-06 06.3 显式接受风险 → 提交成功 :: tripId=3
[PASS] FR-10 10.1 一次对话落一条 run，可按 runId 回查 :: cf5d987f-a5db-44d1-96ab-94b674bb07e3
[PASS] FR-02 02.1 任务图依赖边齐全（PLAN→INTENT、PREVIEW/REVIEW→PLAN） :: INTENT → PLAN → PREVIEW → REVIEW → REPLAN → PREVIEW#1 → COMMIT
[PASS] FR-02 02.2 PREVIEW ‖ REVIEW 实际并行（时间区间重叠） :: preview=[2026-11-19T14:29:32..2026-11-19T14:29:32] review=[2026-11-19T14:29:32..2026-11-19T14:29:32]
[PASS] FR-02 02.3 审核触发 REPLAN，重规划后的 Diff 由 PREVIEW 重派（attempt=1 + replacesTaskId） :: REPLAN（无可自动修复项）→ 仅重建 PREVIEW#1；replaces=20423901-789e-4ac2-9f43-59dd6665e04e
[PASS] FR-04 04.1 任务协议字段齐全（receiver/taskType/status/summary） :: receivers=intent-agent,planner-agent,executor-agent,reflect-agent
[PASS] FR-10 10.2 工具调用逐条落 trace，且成功率 100% :: 45 次调用，工具=search_hotel,search_attraction,calculate_route,search_restaurant
[PASS] FR-04 04.2 SSE 事件含 run / delegate / plan_update / complete :: events=9 [start>run>delegate>intent>planning>delegate>tool_call>tool_result>complete] recover=0
[PASS] FR-07 07.1 规划并提交一趟两日游（用于路线段） :: tripId=4
[PASS] FR-07 07.2 route-legs 返回逐日相邻点路线段，字段完整 :: legs=7 sources={"estimated":7}
[PASS] FR-05 05.1 system/status 如实上报 provider（不泄露密钥） :: routeProvider=estimated geoProvider=osm amapConfigured=false
[PASS] FR-05 05.2 无 Key 时明确降级为 estimated（不伪造真实来源） :: 全部 7 段 estimated
[PASS] FR-10 10.3 未登录访问 runs → 401 :: HTTP 401
[PASS] FR-10 10.4 他人 run 一律 404（跨用户隔离） :: user2=acc772558 → 404

== 汇总（按 FR） ==
PRE: 1 PASS / 0 FAIL
FR-01: 4 PASS / 0 FAIL
FR-02: 3 PASS / 0 FAIL
FR-04: 2 PASS / 0 FAIL
FR-05: 2 PASS / 0 FAIL
FR-06: 3 PASS / 0 FAIL
FR-07: 2 PASS / 0 FAIL
FR-08: 3 PASS / 0 FAIL
FR-09: 3 PASS / 0 FAIL
FR-10: 4 PASS / 0 FAIL

报告: D:\travel-agent\.evidence\mysql84-20261119-142900\fr-acceptance.json  (27/27 PASS, 3.2s)

- fr-acceptance exit=0
- 重启前基线: trips=4 runs=8 itinerary=10

## 4. 重启恢复
- backend 重启后: trips=4 runs=8 itinerary=10
- backend 重启数据一致: True

## 5. 重启 mysqld（后端进程不动）
- mysqld 重新监听: True
- 后端在 mysqld 重启后恢复读: True
- mysqld 重启后新规划: status=NEED_CONFIRMATION（写链路恢复）

## 6. 结论
- MySQL 版本: 8.4.10
- Flyway: 17	9	0；表数量: 25
- 核心链路: fr-acceptance exit=0
- backend 重启一致: True；mysqld 重启恢复: True