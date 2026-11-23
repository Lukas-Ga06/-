# 数据删除流程（账号 / 行程 / 照片 / 会话 / 派生数据）

> 状态：**账号注销 API 已实现（H2 已验证；MySQL/S3 等未实测）** | 最后核对：2026-11-22 | 事实来源：`AuthController.java`、`AccountDeletionService.java`、`AccountDeletionServiceTest.java`、`AccountDeletionApiTest.java`、`docs/api/openapi.json`、`TripService.java`、`PhotoStore.java`、`data-model.md`、`V1`–`V23` 迁移脚本

## 0. 结论（先看这里）

- **后端已实现账号注销**：`DELETE /api/auth/me`，请求体 `{"password":"..."}`；前端在个人中心（`/profile`）的「危险区 · 注销账号」入口，要求输入当前密码并勾选“不可恢复”确认。
- **删除不可恢复**：账号、全部行程、照片、会话、Agent 追踪、个人知识库、LLM 用量计数等在**一个数据库事务**内删除；照片文件在事务提交后通过 `PhotoStore.deleteQuietly` 清理。
- **照片清理失败只 WARN**：不重试、不阻断已提交的账号删除；失败会留下不可再通过接口访问的孤儿对象，需要运维孤儿扫描兜底。
- 用户仍**可以**删除自己的行程、单张照片、单个会话；**账号级数据导出仍未实现**（仅单行程 PDF 路书）。
- 手工 SQL 已降级为**运维兜底**：API 可用时必须用 API，不得用手工 SQL 替代产品能力。

### 0.1 已实现语义（来自 `AuthController#deleteMe` + 测试）

| 场景 | HTTP / code | message / 行为 | 数据 |
| --- | --- | --- | --- |
| 请求体缺失、`{}`、密码空白 | `400` | `请输入密码确认注销` | 不删除，登录态保持 |
| 密码错误 | `401` | `密码错误`（不区分账号是否存在） | 不删除，登录态保持 |
| 未登录 / 用户已不存在（含重复注销） | `401` | 未登录或用户不存在 | 不删除 |
| 成功 | `200`（`data:null`） | 清 Cookie（`Max-Age=0`） | 事务内删除本人数据；提交后删照片文件 |
| 并发注销 | 一个成功，其余 `401` | 锁/删除行数为 0 时回滚并返回 401 | 不会 500 |

### 0.2 证据与边界

- 实现方记录：`mvn verify` 通过（后续 **616 tests，0 failures**，覆盖 JaCoCo / Checkstyle / SpotBugs）；新增 `AccountDeletionServiceTest` / `AccountDeletionApiTest`，含「外层事务回滚后数据恢复且照片不删」的用例。**本任务未重跑 `mvn verify`**。
- H2 实测（实现方）：错误密码 401 后仍可访问 `/me`；正确密码 200 后 `/me` 401、再登录 401。
- **未实测**：MySQL 8 实机的注销全过程、S3 对象存储、旧办公/学习表（`daily_report` 等已无实体/接口）。旧 JWT/同名重注册与内存 RAG 刷新已由 V24 代码收口（见 §2.1/§3.1），但仍需在预发环境端到端复验。
- OpenAPI 快照已更新（paths=55, schemas=107），前端类型已重新生成；**快照只声明 200 响应**，400/401 语义以本文与测试为准。

---

## 1. 当前 API 现状（已实现能力）

| 能力 | 接口 | 行为 | 代码位置 |
| --- | --- | --- | --- |
| 注册 | `POST /api/auth/register` | 创建账号（BCrypt 密码） | `AuthController#register` |
| 登录 / 登出 | `POST /api/auth/login` / `POST /api/auth/logout` | 签发/清除 httpOnly Cookie | `AuthController#login/logout` |
| 查看自己 | `GET /api/auth/me` | 只返回当前账号信息 | `AuthController#me` |
| **注销账号** | **`DELETE /api/auth/me`** | 密码二次确认；事务内删本人全部数据；提交后删照片；清 Cookie | `AuthController#deleteMe`、`AccountDeletionService#deleteAccount` |
| 删除行程 | `DELETE /api/trips/{id}` | 事务内按序删照片元数据、账单、操作历史、行程项、旅行事件、关联日程/备忘、执行台账，再删行程；照片文件在提交后删除 | `TripService#delete` |
| 删除照片 | `DELETE /api/photos/{id}` | 校验归属后删元数据与文件 | `PhotoController` |
| 删除会话 | `DELETE /api/agent/conversations/{conversationId}` | 删除该会话消息与标题覆盖 | `ConversationService#delete` |
| 清空当前会话 | `POST /api/agent/conversation/clear` | 按会话 ID 清理 | `AgentController` |
| 重命名会话 | `PATCH /api/agent/conversations/{conversationId}`（V23 起） | 只写标题覆盖 | `AgentController` / `ConversationService#rename` |
| **账号级数据导出** | **不存在** | 仅单行程 PDF 路书 | `TripController#guidebook` |

## 2. 删除范围（按表，与 `AccountDeletionService` 一致）

MySQL 模式下库内共 26 张表（21 张实体表 + 6 张退役旧表，见 [数据模型](../guide/data-model.md) §7）。账号注销的事务内删除顺序与条件如下：

| 数据 | 表 | 删除条件 | 备注 |
| --- | --- | --- | --- |
| Agent 追踪 | `tool_call_trace` | `user_id = ?` | 先于 task/run |
| | `agent_task` | `user_id = ?` | |
| | `agent_run` | `user_id = ?` | |
| 写意图幂等台账 | `agent_command_dedupe` | `user_id = ?` | |
| 执行台账 / 待确认方案 | `plan_execution`、`pending_plan` | `user_id = ?` | |
| 操作历史 / 记账 | `operation_history`、`expense` | `user_id = ? OR trip_id IN (本人 trip)` | 兼容 `user_id` 为空的旧行 |
| 会话 | `conversation_message`、`conversation_meta` | `user_id = ?` | |
| 备忘 / 日程 | `memo`、`schedule_item` | `user_id = ?` | |
| LLM 用量 | `llm_quota_usage` | `user_name = ?` | 唯一按用户名计数的表 |
| 个人知识库 | `rag_chunk` | `document_id IN (本人 rag_document)` | 先块 |
| | `rag_document_version` | `user_id = ? OR document_id IN (本人 rag_document)` | 再版本 |
| | `rag_document` | `user_id = ?` | **公共知识 `user_id IS NULL` 保留** |
| 行程本体 | `trip_event` | `trip_id IN (本人 trip)` | 先事件 |
| | `itinerary_item` | `trip_id IN (本人 trip)` | 再条目 |
| | `trip_photo` | `user_id = ?` | 先收集 `stored_name` |
| | `trip` | `user_id = ?` | |
| 账号 | `users` | `id = ?` | 最后删；并发时 0 行 → 401 |
| 照片文件 | 本地 `data/photos/` 或 S3 bucket | `trip_photo.stored_name` | 事务提交后删除 |
| 退役旧表（`daily_report` 等 6 张） | — | **API 不删除** | 已无实体/接口；历史库如有数据按 [scripts/cleanup-legacy-tables.sql](../../scripts/cleanup-legacy-tables.sql) 先只读盘点再 opt-in 清理（§4） |
| Redis 限流键 | `ratelimit:user:<username>*` | **API 不删除** | TTL 默认 300 秒自然过期；要求立即清可运维 `SCAN`/`DEL` |
| 内存 RAG 索引 | 进程内向量库 | 注销提交后自动 `reload()`（V24） | 见 §2.1 |
| 日志 / 备份 | 容器日志、`mysqldump` 备份 | 不删除 | 随保留周期过期，见 §7 |

> **没有外键**：表之间靠逻辑关联（`run_id`/`trip_id`/`document_id`），删除顺序由 Service 保证；手工 SQL 必须照抄顺序。

### 2.1 内存 RAG 索引（V24 起已收口）

注销事务提交后，`AccountDeletionService` 会调用 `VectorStore.reload()` 重建进程内索引（失败只 WARN，重启自愈），已删用户的个人知识块不会继续留在内存里可检索。索引重建放在 `afterCommit`：事务内重建会读到未提交的删除，一旦回滚就与数据库不一致。

验证：`AccountDeletionVectorReloadTest` 用 spy 断言注销提交后 `reload()` 恰好调用一次；MySQL/S3 实机仍未演练（见 §7）。

## 3. 接口契约（已实现）

```
DELETE /api/auth/me
认证：登录态（Cookie 或 Authorization）
请求体：{"password":"<当前密码>"}
成功：200 {"code":0,"message":"success","data":null}
      + Set-Cookie: travel_agent_token=; Max-Age=0
失败：400 未提供密码（"请输入密码确认注销"）
      401 密码错误 / 未登录 / 用户已不存在（重复注销）
      429 触发 /api/auth/** 限流
```

实现要点：

1. **事务**：全部数据库删除在一个 `@Transactional` 内；`users` 删除 0 行时抛 401 触发回滚，不落 500。
2. **照片**：事务提交后（`afterCommit`）才调用 `PhotoStore.deleteQuietly`；单文件失败只 WARN，不重试、不恢复账号。
3. **幂等**：重复调用返回 401（用户已不存在）。
4. **响应头/Cookie**：只有成功删除后才清 Cookie；失败路径保持登录态，便于重试。
5. **审计**：应用日志只记 `userId、删除行数、照片文件数`，不记用户名/密码/文件名；当前**没有独立审计表**。

### 3.1 删除后的旧 JWT 行为（V24 起）

- JWT 携带 `uid`（用户主键）与 `ver`（`users.token_version`）。认证过滤器每次校验：uid 对应用户仍存在、token 里的用户名与库里一致、`ver` 等于库里版本；任一不符即 `401`。
- 注销时先 `token_version + 1`，再在同一事务里删除 `users` 行。因此旧 token **立即失效**，不再依赖“业务接口恰好会查用户”。
- **同名重新注册不会串号**：新账号得到新的自增 `uid`，旧 token 的 uid 查不到用户；不需要墓碑、也不需要禁用用户名。
- **一次整体失效**：V24 上线后，没有 uid/ver 的历史 token 全部被拒绝，所有在线会话需要重新登录一次。这是刻意的取舍，必须在发布记录/公告里写明。
- **滚动发布注意**：旧实例签发的 token 没有 uid/ver，新实例会拒绝；新旧实例并存期间用户可能反复掉登录。建议一次性切换全部实例，或接受切换窗口内的重新登录。回滚到 V24 之前的代码是兼容的（多出的 `token_version` 列不被旧实体映射）。
- 改密/踢下线可复用同一机制：把该用户 `token_version + 1` 即可吊销其全部已签发 token（当前仅注销路径自动执行）。
- 测试：`AccountDeletionApiTest`（注销后旧 token 401、同名重注册后旧 token 仍 401、版本 +1 后旧 token 401 且重新登录可用）、`JwtServiceTest`（无 uid/ver 的历史 token 被拒绝）。

## 4. 运维手工删除（仅兜底，必须 DBA 审核）

> **API 可用时必须用 API**。本节仅用于：API 故障、历史脏数据、退役旧表清理（另有 opt-in 脚本 [scripts/cleanup-legacy-tables.sql](../../scripts/cleanup-legacy-tables.sql)，默认只读）、事故处置。执行前必须：**备份**（见 [备份/恢复](../operations/backup-restore.md)）、**双人复核**、**在低峰**、**保留执行记录**。以下 SQL 以 MySQL 8.4 为例，`@uid`/`@uname` 必须先人工确认。

```sql
-- 0) 先确认目标账号（不要用模糊匹配）
SELECT id, username, nickname, created_at FROM users WHERE username = '<待删除用户名>';
SET @uname := '<待删除用户名>';
SET @uid := (SELECT id FROM users WHERE username = @uname);

-- 1) 照片对象清单：先导出到受控文件，供删除对象存储
SELECT stored_name FROM trip_photo WHERE user_id = @uid;

-- 2) 退役旧表是否存在（API 不删这些；存在且有数据才需要处理）
SELECT table_name FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('daily_report','mail_item','meeting_minutes','invoice','learning_artifact','health_record');

-- 3) 事务内按依赖顺序删除（与 AccountDeletionService 对齐）
START TRANSACTION;

DELETE FROM tool_call_trace WHERE user_id = @uid;
DELETE FROM agent_task WHERE user_id = @uid;
DELETE FROM agent_run  WHERE user_id = @uid;
DELETE FROM agent_command_dedupe WHERE user_id = @uid;
DELETE FROM plan_execution WHERE user_id = @uid;
DELETE FROM pending_plan   WHERE user_id = @uid;

DELETE FROM operation_history
 WHERE user_id = @uid OR trip_id IN (SELECT id FROM trip WHERE user_id = @uid);
DELETE FROM expense
 WHERE user_id = @uid OR trip_id IN (SELECT id FROM trip WHERE user_id = @uid);

DELETE FROM conversation_message WHERE user_id = @uid;
DELETE FROM conversation_meta    WHERE user_id = @uid;
DELETE FROM memo           WHERE user_id = @uid;
DELETE FROM schedule_item  WHERE user_id = @uid;
DELETE FROM llm_quota_usage WHERE user_name = @uname;

DELETE FROM rag_chunk
 WHERE document_id IN (SELECT id FROM rag_document WHERE user_id = @uid);
DELETE FROM rag_document_version
 WHERE user_id = @uid OR document_id IN (SELECT id FROM rag_document WHERE user_id = @uid);
DELETE FROM rag_document WHERE user_id = @uid;

DELETE FROM trip_event WHERE trip_id IN (SELECT id FROM trip WHERE user_id = @uid);
DELETE FROM itinerary_item WHERE trip_id IN (SELECT id FROM trip WHERE user_id = @uid);
DELETE FROM trip_photo WHERE user_id = @uid;
DELETE FROM trip       WHERE user_id = @uid;

-- 退役旧表：确认存在后再执行（示例，逐表确认）
-- DELETE FROM daily_report     WHERE user_id = @uid;
-- DELETE FROM mail_item        WHERE user_id = @uid;
-- DELETE FROM meeting_minutes  WHERE user_id = @uid;
-- DELETE FROM invoice          WHERE user_id = @uid;
-- DELETE FROM learning_artifact WHERE user_id = @uid;
-- DELETE FROM health_record    WHERE user_id = @uid;

DELETE FROM users WHERE id = @uid AND username = @uname;
COMMIT;
```

注意事项：

- 先 `SELECT COUNT(*)` 记录每张表删除前的行数，作为审计证据；删除后复查 0 行。
- **不要只删 `users` 行**：会留下全部孤儿数据。
- `rag_document` 的公共知识 `user_id IS NULL` 不能删；只删该用户的个人文档。
- **手工 SQL 与 API 的覆盖差异**：API 会清照片文件、提交后重建内存 RAG（V24），但不会删退役旧表、不会清 Redis 限流键；手工 SQL 不会删照片文件、不会重建内存 RAG、不会清 Redis，退役旧表见 `cleanup-legacy-tables.sql`。
- `llm_quota_usage` 删的是用户名文本；改过用户名的历史计数无法再关联。
- **Redis 限流键**含用户名，TTL 默认 300 秒；立即清除用 `SCAN`/`DEL` 精确删除（不要 `FLUSHALL`）。
- 手工删过 `rag_*` 后，重启 backend（启动会全量重建索引）再做 §6 验证；API 注销路径已自动重建。

## 5. 对象存储清理

照片对象的键是 `trip_photo.stored_name`（32 位十六进制 UUID + 图片扩展名），**没有用户/行程前缀**，不能按前缀批量删除。`.env` 的 `S3_*`（compose 映射为 `TRAVEL_PHOTOS_S3_*`）决定连哪个 bucket。

**API 路径（推荐）**：事务提交后由 `AccountDeletionService` 对每个 `stored_name` 调 `PhotoStore.deleteQuietly`；失败只 WARN。因 `trip_photo` 行已删，失败后**无法再从数据库反查用户与对象名**——运维只能做孤儿对象扫描，因此生产应监控该 WARN 日志（目前无指标，见 §9）。

**手工路径（兜底）**：

| 存储 | 删除方式 | 校验 |
| --- | --- | --- |
| 本地磁盘（`PHOTOS_STORE=local`） | 删除 `/app/data/photos/<stored_name>`（宿主卷 `backend-data`） | 文件不存在（404）；目录中无残留同名文件 |
| S3 兼容（`PHOTOS_STORE=s3`） | 按 §4 导出的 `stored_name` 逐个 `DeleteObject`（或用对象存储控制台批量导入清单）；bucket/AK/SK 来自 `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `HeadObject` 返回 404；清单数量与删除前一致 |

对象删除必须在数据库提交**之后**执行；建议做一次孤儿扫描：列出存储中的对象，与 `trip_photo.stored_name` 比对，孤儿对象按运维策略清理。**S3 版本控制/生命周期/备份中的历史对象不会被 API 删除**，需在隐私政策与 S3 生命周期策略中说明。

## 6. 删除后验证

### 6.1 API 删除（推荐）

| # | 检查 | 期望 |
| --- | --- | --- |
| 1 | 删除响应 | `200`、`data:null`，`Set-Cookie` 含 `Max-Age=0` |
| 2 | 旧 Cookie/JWT 访问 `/api/auth/me`、`/api/trips` | `401` |
| 3 | 原账号重新登录 | `401` |
| 4 | 数据库按 `user_id`/`user_name` 计数（§2 各表） | 全部为 0 |
| 5 | 照片 URL（旧票据未过期时） | `404`（对象已删；若 WARN 失败则可能仍 200，需孤儿扫描） |
| 6 | 其他用户数据 | 不受影响（`AccountDeletionServiceTest` 已覆盖） |
| 7 | 内存 RAG | 重启/reload 后检索不到本人文档（**待补测试**） |
| 8 | Redis 限流键 | 无残留，或确认最多 300 秒内自然过期 |
| 9 | 备份库 | **仍可能有历史数据**，随备份保留周期过期（需在隐私政策说明） |

### 6.2 手工删除（§4）

按 6.1 的 4–9 项逐项核对，并额外附：每表删除前行数、执行人/复核人、对象清单、重启/reload 证据。

## 7. 审计与留存

- **必须留存**：删除请求工单、执行时间、执行人/复核人、账号标识（建议哈希或 userId）、删除前后计数、关联 traceId、对象删除结果。留存时长由法务确定（占位：`[留存时长待法务确认]`）。
- **不得留存**：被删除的消息正文、照片、行程内容副本（证据只需计数与清单，不保留内容）。
- **备份**：删除不追溯清理历史备份；备份保留期需写进隐私政策，并在备份轮转后自然消失。
- **日志**：应用日志只记 `账号删除完成: userId=..., 删除行数=..., 照片文件数=...`，不含用户名/密码/文件名；`RedisRateCounter` 的 fail-open WARN 可能带用户名/IP（见 [Runbook 附录 A](../operations/runbook.md)）；nginx 访问日志已改用 `without_args` 只记 `$uri`（待真实容器验证）。日志保留期由部署方轮转策略决定（`backend`/`web` 为 10m × 3），删除请求不追溯清理已归档日志。
- **法务留存例外**：如法务/安全要求保留特定日志、审计记录或交易凭证，必须单独列出类别、期限与访问权限（占位：`[需法务明确]`），不能笼统写“全部删除”。

## 8. 发布验收清单

- [x] 账号删除 API 已实现（`DELETE /api/auth/me`，密码二次确认，200 清 Cookie）。
- [x] H2 集成测试覆盖：缺密码 400、密码错误 401 不删数据、成功 200 删本人全部表、其他用户保留、重复注销 401、事务回滚数据与照片恢复。
- [ ] **MySQL 8 实机**跑一次注销并核对 26 张表/对象存储（SQL 方言、事务、`afterCommit` 顺序）。
- [ ] **S3/MinIO** 实测 `deleteQuietly` 与孤儿扫描；确认版本控制/生命周期下的历史对象口径。
- [x] **内存 RAG**：注销提交后 `vectorStore.reload()`，有单元/集成测试（§2.1）。
- [x] **同名重注册/旧 JWT** 已实现（V24 uid + token_version，§3.1）；上线后所有旧 token 失效一次，需在发布记录/公告里写明。
- [ ] 照片清理失败 WARN 有监控/巡检（当前无指标）。
- [ ] 账号级导出可用（JSON/CSV），覆盖行程、记账、备忘、日程、会话、照片清单。
- [ ] 隐私政策、第三方服务说明与本文同步；法务确认留存期、备份口径与法务留存例外。
- [ ] 用一次**测试账号**在预发环境端到端删除并留存证据（真实用户数据不得用于演练）。

## 9. 未验证 / 待实现

| 项 | 状态 |
| --- | --- |
| 账号删除 API | **已实现并通过 H2 测试**（MySQL/S3 未实测） |
| 账号级数据导出 | **未实现**（仅单行程 PDF） |
| MySQL 8 实机注销 | **未验证** |
| S3 对象删除与版本控制 | **未验证** |
| 退役旧表（`daily_report` 等） | API **不覆盖**；历史库需运维手工处理 |
| 内存 RAG 刷新 | **已实现**：注销提交后自动重建（V24，§2.1）；MySQL 实机未验 |
| 旧 JWT / 同名重注册 | **已修复**（V24：uid + token_version，§3.1）；旧 token 整体失效一次 |
| 照片清理失败告警 | **无指标**，只有 WARN 日志 |
| 审计表 | **不存在**，只有应用日志 |
| 手工 SQL 流程 | 文档已就绪，**尚未在演练环境执行** |
| 留存期数值 / 法务留存例外 | **待法务确认** |

相关文档：[隐私政策](privacy-policy.md) · [第三方服务说明](third-party-services.md) · [备份/恢复与迁移演练](../operations/backup-restore.md) · [数据模型](../guide/data-model.md) · [故障处理手册](../operations/runbook.md)