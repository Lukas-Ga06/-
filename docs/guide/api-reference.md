# REST API 参考

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.16 / Node 20） | 最后验证：2026-11-25 | 事实来源：`travel-agent-server/src/main/java/com/travel/agent/controller/`、`dto/`、`docs/api/openapi.json`

> **机器契约入口**：`GET /v3/api-docs` 是字段/类型/必填约束的事实来源，入库快照为 [`docs/api/openapi.json`](../api/openapi.json)，前端类型由它生成。快照的生成、校验与破坏性变更流程见 [api-contract.md](api-contract.md)。本文负责人读语义（业务规则、字段口径、错误码、示例）；两者冲突时，字段类型以 OpenAPI 快照为准，业务语义以本文为准。

本文是 AI 智能旅行助手后端（Spring Boot 3）的**逐接口契约手册**，面向前端开发者与第三方集成者。
所有接口、字段、校验规则和错误码均取自 `travel-agent-server` 源码，未使用推测内容。

- 源码根：`travel-agent-server/src/main/java/com/travel/agent/`
- 接口总数：**70 个**（12 个业务 Controller 的 67 个 + 渠道包 3 个），清单见第 14、15 章
- 已退役：`/api/office/**`、`/api/report/**`、`/api/health/**`、`/api/learning/**`（Controller 与实体已删除，调用 404）
- 每个接口标注 `类名#方法名:行号`，可直接回源码核对

---

## 1. 通用约定

### 1.1 基础信息

| 项 | 值 |
| --- | --- |
| 监听端口 | `8080`（`server.port`） |
| 路径前缀 | 无统一前缀，各接口自带 `/api/...` |
| 请求/响应编码 | `UTF-8` |
| 请求体 | `application/json`；上传为 `multipart/form-data` |
| 日期 / 日期时间 | `yyyy-MM-dd` / `yyyy-MM-ddTHH:mm:ss`（`LocalDateTime.toString()`） |
| 时区 | `Asia/Shanghai`（`travel.timezone`，可用 `GET /api/system/status` 的 `timezone` 确认） |
| 跨域 | `travel.cors.allowed-origins` 白名单，`allowCredentials=true`（Cookie 跨域需带凭证） |

### 1.2 认证方式（三种凭证，优先级从高到低）

`JwtAuthenticationFilter` 依次解析，**命中一种即停止**：

| 顺序 | 传递位置 | 适用场景 | 说明 |
| --- | --- | --- | --- |
| 1 | `Authorization: Bearer <JWT>` | curl / e2e 脚本 / 非浏览器客户端 | 请求头，优先级最高 |
| 2 | Cookie `travel_agent_token`（httpOnly） | **浏览器主路径** | 登录/注册成功后由服务端 `Set-Cookie` 种下 |
| 3 | 查询参数 `?ticket=<票据>` | `EventSource`（SSE）与 `<img>` 原图 | **只接受短期只读票据**，不接受登录 JWT；且只对 `GET /api/agent/stream`（`agent-stream` 用途）与 `GET /api/photos/{id}/raw`（`media-read` 用途）生效 |

登录 Cookie 属性（`AuthCookieService`）：

| 属性 | 值 |
| --- | --- |
| 名称 | `travel_agent_token` |
| `HttpOnly` | `true`（JS 不可读，防 XSS 窃取） |
| `Secure` | `travel.jwt.cookie-secure`，默认 `false`（生产应设 `true`） |
| `SameSite` / `Path` | `Lax` / `/` |
| `Max-Age` | 与 JWT 有效期一致，默认 `travel.jwt.expire-hours=72` 小时 |

免登录白名单（`SecurityConfig#securityFilterChain:58-64`）：

| 路径 | 说明 |
| --- | --- |
| `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout` | 无需登录 |
| `/api/channels/**` | 渠道入口，以密钥 / 微信签名自证身份（见第 15 章） |
| `/h2-console/**`（**仅** `H2_CONSOLE_ENABLED=true` 时）、`/error`、`/actuator/health`、`OPTIONS *` | 开发、探活、预检；控制台默认关闭，关闭时匿名访问返回 `401` |
| **其余全部接口** | 必须已登录（`anyRequest().authenticated()`），`GET /api/auth/me` 也需登录 |

会话策略为 `STATELESS`（无服务端 Session），身份完全来自 JWT；未登录返回 `401`。
演示账号 `demo / demo123`（`travel.demo-account.enabled=false` 可关闭）。

### 1.3 Access-Ticket 短期只读票据

| 项 | 值 |
| --- | --- |
| 签发接口 | `POST /api/access-ticket` |
| 是否需要登录 | **需要**（Cookie 或 `Authorization` 头） |
| 有效期 | `travel.jwt.ticket-ttl-seconds`（环境变量 `TICKET_TTL_SECONDS`），默认 `300` 秒 |
| 用途（`purpose`） | `agent-stream`（缺省）/ `media-read`，写入票据并参与校验 |
| 传递位置 | 查询参数 `?ticket=<票据>` |
| 是否落库 | 否，无状态校验：内容为 `v2|aud|expiresAt|username` + HMAC-SHA256 |
| 可否撤销 | 更换 JWT 密钥即全部失效 |

**票据用途与路径必须匹配**（`AccessTicketService` 签发、`JwtAuthenticationFilter` 校验）：

| `purpose` | 唯一可用路径 | 用途 |
| --- | --- | --- |
| `agent-stream`（缺省） | `GET /api/agent/stream` | `EventSource` 建流 |
| `media-read` | `GET /api/photos/{id}/raw` | `<img>` 加载原图 |

照片票据不能开 SSE，SSE 票据也不能读照片，用途不匹配一律 `401`；`purpose` 取其它值签发时返回 `400`。票据格式为
`base64url(payload) + "." + base64url(HmacSHA256(payload))`（两段均无 padding），**不是 JWT**，不能用于 `Authorization` 头，
也不能用于任何写接口或自我续期。签发后：

```
POST /api/access-ticket?purpose=agent-stream
Cookie: travel_agent_token=<JWT>
→ {"code":0,"message":"success","data":{"ticket":"djJ8YWdlbnQtc3RyZWFt...","expiresInSeconds":300,"purpose":"agent-stream"}}

GET /api/agent/stream?message=你好&ticket=...        # 只能用 agent-stream 票据
GET /api/photos/12/raw?ticket=...                    # 只能用 media-read 票据
```

**仅凭票据认证（没有 Cookie / `Authorization`）的请求 = 只读会话**：记账 / 备忘 / 日程三类直接写入意图被拒绝，
`message` 为 `当前连接为只读票据会话，记账/备忘/日程请在网页端操作。`（`status` 仍是 `COMPLETED`，不按错误处理；
拦截集合见 `AgentEngine.TICKET_READONLY_BLOCKED_INTENTS`）；规划与改行程仍可用（只产出待确认方案，提交需
`POST /api/agent/confirm`，而 POST 不接受票据）。批量清空日程（`CLEAR_SCHEDULE`）与发票报销（`RECORD_INVOICE`）
同属「只产出待确认方案」的一类，同样不受票据只读拦截（报销的 OCR 识别只读、不落库），确认提交仍需登录。
带 Cookie 的浏览器请求行为不变。

**升级注意**：旧版票据没有 `purpose` 段，服务端一律按 `agent-stream` 处理——上线后最多 5 分钟内，旧的图片链接会 `401`，刷新页面重新签票即恢复。

### 1.4 统一响应包

除 SSE、PDF、原图与微信渠道外，所有 JSON 接口返回 `ApiResponse<T>`（`dto/ApiResponse.java`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | `int` | 成功恒为 `0`；失败时与 HTTP 状态码一致 |
| `message` | `String` | 成功为 `"success"`；失败为可直接展示的中文提示 |
| `data` | `T` | 业务数据；失败时为 `null` |
| `pagination` | `Object`（可选） | **仅列表接口**出现（见 §1.6）；未分页的响应不输出该键（`@JsonInclude(NON_NULL)`） |

```json
{"code":0,"message":"success","data":{}}
{"code":0,"message":"success","data":[...],"pagination":{"total":123,"limit":20,"offset":0,"hasMore":true}}
{"code":404,"message":"行程不存在或无权访问","data":null}
```

不套统一包的接口：

| 接口 | 实际返回 |
| --- | --- |
| `GET /api/agent/stream`、`POST /api/agent/stream`、`GET /api/agent/runs/{runId}/events` | `text/event-stream`（SSE，见 §5.3/§5.4/§5.13.1） |
| `GET /api/trips/{id}/guidebook.pdf` | `application/pdf` 字节流 |
| `GET /api/photos/{id}/raw` | 图片字节流（`Content-Type` 由文件头推导） |
| `GET/POST /api/channels/wechat` | 纯文本 / XML（微信协议） |

### 1.5 错误码 / HTTP 状态码映射

`GlobalExceptionHandler` 约定 **body 里的 `code` 与 HTTP 状态码一致**：

| code / HTTP | 触发条件 | 典型 message |
| --- | --- | --- |
| `400` | `@Valid` 校验失败、请求体不可解析、缺参数、`BusinessException(400)` | `<字段> <校验提示>`；`结束日期不能早于开始日期`；`未知的行程项状态: xxx`；`请输入密码确认注销` |
| `401` | 未登录 / 登录过期（安全链）、登录失败、用户不存在、密码错误 | `未登录或登录状态已过期`；`用户名或密码错误`；`密码错误` |
| `403` | `AccessDeniedException` | `没有权限执行该操作` |
| `404` | 资源不存在或无权访问 | `行程不存在或无权访问`；`照片不存在`；`文档不存在`；`版本不存在` |
| `409` | 唯一约束/数据冲突、乐观锁冲突 | `数据冲突，请检查是否重复提交或文本过长` |
| `429` | 触发限流（响应带 `Retry-After`） | `操作过于频繁，请 N 秒后再试` |
| `503` | 依赖服务不可用 / 线程池满（如 SSE 续传无法调度） | `服务器繁忙，请稍后重试`；天气依赖型修改的 `status=FAILED` 会说明原因 |
| `500` | 未捕获异常（不返回内部细节） | `服务端异常，请稍后重试` |

> 退役模块的 `422`（旧发票模块「发票内容不可提取 / OCR 不可用」）与发票重复记账 `409` 不再存在。
> V32 的发票报销不新增错误码：识别不可用/失败是 `POST /api/agent/chat` 的 `status=COMPLETED` + 明确文案（见 §5.2）。

### 1.6 列表分页契约（第九批新增）

**请求参数**（query，全部可选，对下表中的列表接口生效）：

| 参数 | 默认 | 归一化规则 |
| --- | --- | --- |
| `limit` | `20` | `> 200` 夹到 `200`；`<= 0` 用默认 `20` |
| `offset` | `0` | 负数按 `0` |

**响应**：`ApiResponse` 顶层新增**可选** `pagination` 键，`data` 仍是数组（不是 `{items:[]}`）；未分页的响应不输出该键
（`dto/ApiResponse.java` 的 `@JsonInclude(NON_NULL)`）。信封仍是 `code`（不是 `success`）：

```json
{"code":0,"message":"success","data":[...],"pagination":{"total":123,"limit":20,"offset":0,"hasMore":true}}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `total` | `long` | 满足条件的总条数 |
| `limit` / `offset` | `int` | 归一化后的生效值（服务端回显） |
| `hasMore` | `boolean` | 是否还有下一页 |

**已接入数据库分页（`Pageable` + `Page`）的 4 个端点**（`util/Paging` 的 `pageable` / `normalizeLimit` / `normalizeOffset`）：

| 端点 | 说明 |
| --- | --- |
| `GET /api/trips` | 行程列表（§3.1） |
| `GET /api/trips/{id}/expenses` | 行程支出列表（§6.1） |
| `GET /api/memos` | 备忘列表（§9.1） |
| `GET /api/reminders` | 提醒板（§8.1）；`data` 仍是 `{items,count,serverTime}`，`items` 分页、`count` 为总数 |

> **行为变化（重要）**：这 4 个端点默认条数从 `500` 降为 **20**。不传 `limit` / `offset` 的老客户端只会拿到第一页 20 条，
> 必须读 `pagination.hasMore` 翻页（或显式传更大的 `limit`，上限 `200`）。

**仍走服务端兜底截断、无分页元数据**的端点：`/api/photos**`、`/api/schedule`（`util/Paging.slice`，默认 `500`）；
`/api/trips/{id}/events` 只做 `limit` 归一化（默认 `20`、上限 `200`），响应不带 `pagination`。

**聚合下推**（响应 JSON 结构不变，只是改成数据库聚合）：`GET /api/expenses/overview` 的分类 / 支付方式 / 行程维度改为
`GROUP BY` 聚合 + 最近 8 条单独查询；`ExpenseService.totalOf` 改 `SUM`；备忘待办数改 `COUNT`。

### 1.7 上传与限流

- 上传上限：Spring multipart `max-file-size=10MB` / `max-request-size=12MB`；照片单张 ≤10MB、单行程 ≤200 张、单用户 ≤500MB。
- 限流（`RateLimitFilter`，默认开启、窗口 300 秒）：`/api/auth/**` 按 IP 10 次；`/api/agent/**` 按用户 30 次；照片上传 60 次；其余 `/api/**` 按用户 600 次。
  - `GET /api/auth/me` 走**独立会话桶** `RATE_LIMIT_SESSION`（默认 60/窗口），避免刷新页面被登录桶（`RATE_LIMIT_AUTH`，默认 10）踢回登录页；前端只把 `401` 当登出。
  - `Retry-After` 最小为 `1`（不再出现 `Retry-After: 0`）；Redis fail-open 时打 WARN 并计 `ratelimit.failopen` 指标。
- 所有失败响应中 `data` 均为 `null`；成功但无返回值的接口 `data` 也为 `null`。

---

## 2. 认证与票据（7 个）

### 2.1 POST /api/auth/register — 注册
鉴权：免登录 ｜ 源码：`AuthController#register:36` ｜ 成功时响应头带 `Set-Cookie: travel_agent_token=...`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `username` | 是 | 3–32 字符，全局唯一 |
| `password` | 是 | 6–64 字符 |
| `nickname` | 否 | ≤32 字符，缺省取 `username` |
```json
// 请求
{"username":"traveler","password":"secret123","nickname":"小旅"}
// 响应
{"code":0,"message":"success","data":{"token":"eyJhbGciOiJIUzI1NiJ9.xxx","id":2,"username":"traveler","nickname":"小旅"}}
```
错误：`400` 用户名/密码为空或长度不符、`用户名已存在`；`429` 限流

### 2.2 POST /api/auth/login — 登录
鉴权：免登录 ｜ 源码：`AuthController#login:44` ｜ 成功时 `Set-Cookie`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `username` | 是 | 用户名 |
| `password` | 是 | 密码（BCrypt 校验） |
```json
// 请求
{"username":"demo","password":"demo123"}
// 响应
{"code":0,"message":"success","data":{"token":"eyJhbGciOiJIUzI1NiJ9.xxx","id":1,"username":"demo","nickname":"演示用户"}}
```
错误：`401` `用户名或密码错误`；`429` 限流

### 2.3 GET /api/auth/me — 恢复登录态
鉴权：登录 ｜ 源码：`AuthController#me:56` ｜ 无请求体（凭 Cookie/头恢复用户资料，`token` 为 `null`）
```json
{"code":0,"message":"success","data":{"token":null,"id":2,"username":"traveler","nickname":"小旅"}}
```
错误：`401` 未登录或登录已过期

### 2.4 POST /api/auth/logout — 登出
鉴权：免登录（幂等）｜ 源码：`AuthController#logout:109` ｜ 无参数；清空登录 Cookie
```json
{"code":0,"message":"success","data":null}
```
错误：`500`（极少数 Cookie 写失败场景）

### 2.5 DELETE /api/auth/me — 注销账号（P0-D）
鉴权：登录 ｜ 源码：`AuthController#deleteMe:92` ｜ 请求体 `{"password":"<当前密码>"}`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `password` | 是 | 当前账号密码；缺失、`{}` 或空白 → `400` |
```json
// 请求
{"password":"secret123"}
// 成功响应（同时 Set-Cookie: travel_agent_token=; Max-Age=0）
{"code":0,"message":"success","data":null}
```
删除范围（单事务）：`tool_call_trace`、`agent_task`、`agent_run`、`agent_command_dedupe`、`plan_execution`、`pending_plan`、`operation_history`、`expense`、`conversation_message`、`conversation_meta`、`memo`、`schedule_item`、`llm_quota_usage`（按 `user_name`）、本人 `rag_chunk`/`rag_document_version`/`rag_document`（公共知识保留）、`trip_event`、`itinerary_item`、`trip_photo`、`trip`、`users`。照片文件在事务提交后由 `PhotoStore.deleteQuietly` 清理（失败只 WARN）。

错误：`400` `请输入密码确认注销`；`401` `密码错误`（不删除、保持登录）、未登录、用户已不存在/重复注销；`429` 限流。

> 语义见 [数据删除流程](../legal/data-deletion.md) §3。V24 起注销会立即吊销旧 token（同名重注册不串号），并在事务提交后重建内存 RAG 索引。OpenAPI 快照目前只声明 `200` 响应，`400/401` 来自代码与 `AccountDeletionApiTest`。

### 2.6 GET /api/auth/me/export — 导出本人数据（数据可携带）
鉴权：登录 ｜ 源码：`AuthController#exportMe:76` ｜ 无参数；身份只取自登录态，因此不存在「传别人 id 导出别人数据」的路径

返回本人全部可见数据的 JSON 快照。`formatVersion` 为 `1`；表格类数据是数组，键名固定 camelCase，时间一律 ISO-8601 字符串，金额仍是数字。

```json
{"code":0,"message":"success","data":{
  "formatVersion":"1","exportedAt":"2026-11-23T19:40:12.345+08:00",
  "account":{"id":2,"username":"traveler","nickname":"小旅","createdAt":"2026-11-20T10:00:00"},
  "counts":{"trips":1,"itineraryItems":1,"expenses":1,"memos":1,"schedules":1,"conversations":1,"messages":1,"knowledgeDocuments":1,"photos":1},
  "trips":[{"id":7,"name":"杭州三日","destination":"杭州","startDate":"2026-12-01","endDate":"2026-12-03","budget":3000.00,"partySize":2,"status":"CONFIRMED","createdAt":"2026-11-22T09:00:00"}],
  "itineraryItems":[{"id":11,"tripId":7,"dayIndex":1,"itemType":"ATTRACTION","title":"西湖漫步","startTime":"2026-12-01T09:00:00","endTime":null,"latitude":30.24,"longitude":120.15,"cost":0.00,"tags":"","description":"","status":"PENDING","startedAt":null,"completedAt":null,"visitedAt":null}],
  "expenses":[{"id":3,"tripId":7,"category":"FOOD","amount":128.50,"note":"午饭","spentAt":"2026-12-01","paymentMethod":"WECHAT","createdAt":"2026-12-01T12:30:00"}],
  "memos":[{"id":5,"content":"带伞","done":false,"createdAt":"2026-11-22T09:10:00","tripId":7,"itineraryItemId":null,"source":"MANUAL","travelDay":null}],
  "schedules":[{"id":9,"title":"高铁出发","date":"2026-12-01","time":"07:30","note":null,"done":false,"recurrence":null,"repeatUntil":null,"reminderDismissedAt":null,"createdAt":"2026-11-22T09:20:00","tripId":7,"itineraryItemId":null,"source":"MANUAL","travelDay":null}],
  "conversations":[{"conversationId":"conv-1","title":"杭州三天","createdAt":"2026-11-22T09:30:00","updatedAt":"2026-11-22T09:35:00","messages":[{"role":"user","content":"帮我规划杭州三天","intent":null,"scope":null,"turnSeq":1,"createdAt":"2026-11-22T09:30:00"}]}],
  "knowledgeDocuments":[{"id":4,"title":"我的杭州笔记","city":"杭州","category":"GUIDE","content":"……","source":"UPLOAD","updatedAt":"2026-11-22T09:40:00"}],
  "photos":[{"id":2,"tripId":7,"originalName":"断桥.jpg","contentType":"image/jpeg","sizeBytes":204800,"createdAt":"2026-11-23T10:00:00","kind":"PHOTO","url":"/api/photos/2/raw"}]
}}
```

**范围与口径**

- 含：账号资料、行程、行程项、记账、备忘、日程、会话与消息、本人上传的知识库文档、照片清单。
- 不含：照片二进制（只给 `/api/photos/{id}/raw`，仍需本人凭证）；`agent_run` / `agent_task` / `tool_call_trace`（只存执行状态与参数摘要，对话正文已在 `conversations` 里）；`rag_document.user_id` 为空的城市公共知识。
- 只读：不写库、不重复插入消息、不清理照片，可重复调用；`counts` 与各数组长度一致。

错误：`401` 未登录；`429` 导出桶限流（`RATE_LIMIT_EXPORT`，默认 5 分钟 3 次，独立于登录桶，不影响页面刷新）。
### 2.7 POST /api/access-ticket — 签发短期只读票据
鉴权：登录 ｜ 源码：`AccessTicketController#issue:21`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 否 | `agent-stream`（缺省，给 `GET /api/agent/stream`）/ `media-read`（给 `GET /api/photos/{id}/raw`）；其它值 `400` |

```json
// 请求：POST /api/access-ticket?purpose=media-read
// 响应（data 多一个 purpose 字段）
{"code":0,"message":"success","data":{"ticket":"djJ8bWVkaWEtcmVhZHwxNzYyNTk4Mjg0fHRyYXZlbGVy.5tG...","expiresInSeconds":300,"purpose":"media-read"}}
```
错误：`400` `purpose 不合法，仅支持 media-read 或 agent-stream`；`401` 未登录。票据仅用于 `?ticket=`，不能配合 `Authorization` 使用。

---

## 3. 行程（14 个）

### 3.1 GET /api/trips — 行程列表
鉴权：登录 ｜ 源码：`TripController#list:40` ｜ 错误：`401`
参数：`limit`（默认 20，上限 200）、`offset`（默认 0）；响应带 `pagination`（见 §1.6）
```json
{"code":0,"message":"success","data":[{"id":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"partySize":2,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-01"}],"pagination":{"total":1,"limit":20,"offset":0,"hasMore":false}}
```
`phase` 由日期派生：`UPCOMING` / `ONGOING` / `PAST` / `CANCELLED`；`daysUntilStart` 仅 `UPCOMING` 有值。

### 3.2 GET /api/trips/map — 首页足迹坐标（P1 扩展）
鉴权：登录 ｜ 源码：`TripController#map` ｜ 错误：`401`
参数：无；已取消行程不计入；坐标可能为 `null`（图集与联网均未命中）
```json
{"code":0,"message":"success","data":[{"tripId":1,"name":"杭州三日","destination":"杭州","status":"PLANNING","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"lat":30.25,"lng":120.16,"phase":"UPCOMING","plannedPlaces":8,"visitedPlaces":0,"visited":false,"actualRoute":[]}]}
```
P1 新增字段（口径见 `TripDtos.TripMapPoint`）：

| 字段 | 说明 |
| --- | --- |
| `phase` | `UPCOMING` / `ONGOING` / `PAST` / `CANCELLED`，由日期派生 |
| `plannedPlaces` | 有坐标的计划地点数 |
| `visitedPlaces` | 实际到访数：**只统计 `visitedAt` 非空的条目** |
| `visited` | `visitedPlaces > 0`；前端「去过」统计只认它 |
| `actualRoute` | 实际到访路线点 `{itemId,title,visitedAt,lat,lng}`，按 `visitedAt` 升序，无坐标点跳过 |

> 计划地点与计划时间**绝不**被当成「去过」；未来行程即使日期已过、没有打卡记录，`visited` 仍为 `false`。

### 3.3 POST /api/trips — 创建行程
鉴权：登录 ｜ 源码：`TripController#create:56`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 否 | ≤200 字符，缺省 `我的旅行` |
| `destination` | 否 | ≤200 字符 |
| `startDate` | 否 | `yyyy-MM-dd`，缺省明天 |
| `endDate` | 否 | `yyyy-MM-dd`，缺省 `startDate + 2 天`（共 3 天） |
| `budget` | 否 | `BigDecimal` ≥ 0，缺省 `5000` |
| `partySize` | 否 | **V2.2 FR-01** 同行人数 1..20，缺省 1；预算的房数/餐费/门票口径随它变化 |
> 跨度上限：`endDate` 不得晚于 `startDate` + 1 年（按日历算，含当天；闰年自动多一天）。
> 只传一端时，先按缺省值补齐再判定。超过返回 `400 行程跨度不能超过 1 年（当前 N 天）`。
```json
// 请求
{"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000,"partySize":2}
// 响应
{"code":0,"message":"success","data":{"id":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"partySize":2,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-08"}}
```
错误：`400` 校验失败 / `结束日期不能早于开始日期` / `开始日期不是有效日期，请使用 yyyy-MM-dd` / `行程跨度不能超过 1 年（当前 N 天）`；`401`

### 3.4 GET /api/trips/{id} — 行程详情
鉴权：登录 ｜ 源码：`TripController#get:62` ｜ 参数：路径 `id` ｜ 响应结构同 §3.3
```json
{"code":0,"message":"success","data":{"id":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"partySize":2,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-01"}}
```
错误：`401`；`404` `行程不存在或无权访问`

### 3.5 PUT /api/trips/{id} — 更新行程
鉴权：登录 ｜ 源码：`TripController#update:68` ｜ 请求体同 §3.3（仅传需要修改的字段）
```json
// 请求
{"name":"杭州四日","endDate":"2026-11-23","budget":6000,"partySize":3}
// 响应
{"code":0,"message":"success","data":{"id":1,"name":"杭州四日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-23","budget":6000.00,"partySize":3,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-01"}}
```
错误：`400` 结束日期早于开始日期 / `缩短后第 N 天的安排会超出范围…` / `行程跨度不能超过 1 年（当前 N 天）`（按合并后的起止日期判定）；`401`；`404`
改期会整体平移行程项时间（保持「第几天 + 时刻」不变）。

### 3.6 DELETE /api/trips/{id} — 删除行程
鉴权：登录 ｜ 源码：`TripController#delete:75` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404`。级联删除行程项、账单、操作历史与照片（含磁盘文件）。

### 3.7 PUT /api/trips/{id}/status — 确认 / 取消 / 恢复
鉴权：登录 ｜ 源码：`TripController#updateStatus:83`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 是 | `PLANNING`（待确认）/ `CONFIRMED`（已确认）/ `CANCELLED`（已取消），大小写不敏感 |
```json
// 请求
{"status":"CONFIRMED"}
// 响应
{"code":0,"message":"success","data":{"id":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"status":"CONFIRMED","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-01"}}
```
错误：`400` `不支持的行程状态: xxx`；`401`；`404`

### 3.8 GET /api/trips/{id}/itinerary — 行程项列表
鉴权：登录 ｜ 源码：`TripController#itinerary:92` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":[{"id":11,"tripId":1,"dayIndex":1,"itemType":"attraction","title":"西湖断桥","startTime":"2026-11-20T09:00:00","endTime":"2026-11-20T11:00:00","latitude":30.259,"longitude":120.148,"cost":0.00,"tags":["室外","免费"],"description":"清晨人少","status":"PENDING","startedAt":null,"completedAt":null,"visitedAt":null,"price":null}]}
```
错误：`401`；`404`

**P1 执行状态字段**（`ItineraryDtos.ItineraryItemDTO`）：

| 字段 | 取值 / 说明 |
| --- | --- |
| `status` | `PENDING`（默认）/ `IN_PROGRESS` / `COMPLETED` / `SKIPPED`；历史数据缺失按 `PENDING` 处理 |
| `startedAt` | 实际开始时间；未开始为 `null` |
| `completedAt` | 完成/跳过时间；未发生为 `null` |
| `visitedAt` | 实际到访时间；**只有 `COMPLETED` 会写它**，足迹与回顾只认这个字段 |

**`price`（参考价 + 来源 + 深链，ADR-006 补齐）**：`cost` 为 `null` 或 `0` 时 `price: null`，否则：

```json
"price": {
  "amount": 520.00, "currency": "CNY",
  "kind": "reference", "label": "参考价",
  "source": "local-estimate", "sourceLabel": "本地估算",
  "disclaimer": "非实时报价：未接入房态/库存，实际价格与可订状态以 OTA 页面为准",
  "links": [
    {"provider":"ctrip","label":"去携程查价","url":"https://hotels.ctrip.com/hotels/list?flexType=1&cityId=17&provinceId=0&districtId=0&countryId=1&checkin=2026-11-20&checkout=2026-11-21","kind":"price"},
    {"provider":"amap","label":"在地图查看","url":"https://uri.amap.com/marker?position=120.148,30.259&name=%E8%A5%BF%E6%B9%96%E6%96%AD%E6%A1%A5&src=travel-agent&coordinate=wgs84&callnative=0","kind":"map"}
  ]
}
```

| 项 | 约定 |
| --- | --- |
| `kind` | 固定 `reference`；当前**没有**任何实时房态/库存源，不许出现「实时/可订/有房」 |
| `source` | 固定 `local-estimate`（静态目录或模型生成的本地估算） |
| `links.kind=price` | 仅**酒店**：携程 canonical 列表（`cityId` 表：北京 `1`、上海 `2`、杭州 `17`，其余城市**不给链接**；缺行程起止日期也不给）；简化的 `?city=` 形式会跳登录页，禁止使用 |
| `links.kind=map` | 所有有坐标的条目：高德 URI，`position={lng},{lat}`、`coordinate=wgs84`（业务坐标是 WGS-84，由高德纠偏） |
| 不可用源 | **大众点评**：实测 `302` 到 `account.dianping.com/pclogin` / `verify.meituan.com`，不能当深链 |

`GET /api/system/status` 同步新增 `priceProvider: "reference-estimate"` 与 `priceHint`，与 `routeProvider` 同口径。

### 3.9 GET /api/trips/{id}/route-legs — 每日路线段（V2.2 FR-07）
鉴权：登录 ｜ 源码：`TripController#routeLegs` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":[
  {"dayIndex":1,"sequence":1,"fromItemId":11,"toItemId":12,"fromTitle":"外滩","toTitle":"豫园",
   "fromLatitude":31.2397,"fromLongitude":121.4998,"toLatitude":31.227,"toLongitude":121.4921,
   "distanceKm":1.9,"walkMinutes":25,"driveMinutes":9,"taxiCost":17.0,"source":"estimated","polyline":[]}]}
```
按天输出「相邻且有坐标」的行程点之间的路段：`sequence` 为当天内段序号（从 1 开始）；
`source` 标注距离来源——`amap`（高德真实路径）或 `estimated`（本地 haversine 估算）。
**`taxiCost` 始终为本地估算**（未接入计价 Provider），前端不得显示成实时价格。
无坐标条目（如「当日交通（打车）」费用汇总行）自动跳过，不产生假路线。

`polyline`（V2.2 收口）是**真实道路几何**，契约固定为：

| 项 | 约定 |
| --- | --- |
| 类型 | `number[][]`，每点 `[lat, lng]`（**纬度在前**） |
| 坐标系 | **WGS-84**（与全仓业务坐标一致；前端渲染前逐点转 GCJ-02） |
| 内容 | 高德 `route.paths[].steps[].polyline` 拼接 → GCJ-02→WGS-84 → Douglas-Peucker 抽稀（12 m 容差），**≤200 点**，首尾点必保留 |
| 缺省 | 字段**常驻不为 null**；`estimated` 段与「真实接口无几何」一律为 `[]`——**绝不返回假点**（ADR-006/ADR-009） |

前端语义：`polyline.length >= 2` 时按几何画线（`source=amap` 实线），否则回退「两端点直连」。
错误：`401`；`404`（他人行程同样 404）

### 3.10 POST /api/trips/{id}/itinerary — 新增行程项
鉴权：登录 ｜ 源码：`TripController#addItem:106`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `title` | 是 | ≤200 字符 |
| `dayIndex` | 否 | 第几天，从 1 开始，缺省 1；不得超过行程天数（越界 `400`） |
| `itemType` | 否 | `attraction`（缺省）/ `hotel` / `restaurant` / `transport` |
| `startTime` / `endTime` | 否 | `HH:mm`，缺省 `09:00` / `11:00`（按行程日期锚定） |
| `cost` | 否 | ≥0 |
| `latitude` / `longitude` | 否 | 坐标 |
| `tags` | 否 | 字符串数组，如 `["室内","文化"]` |
| `description` | 否 | 说明文本 |
```json
// 请求
{"dayIndex":1,"itemType":"restaurant","title":"楼外楼","startTime":"12:00","endTime":"13:00","cost":180.00,"tags":["餐饮"],"description":"西湖边"}
// 响应
{"code":0,"message":"success","data":{"id":12,"tripId":1,"dayIndex":1,"itemType":"restaurant","title":"楼外楼","startTime":"2026-11-20T12:00:00","endTime":"2026-11-20T13:00:00","latitude":null,"longitude":null,"cost":180.00,"tags":["餐饮"],"description":"西湖边"}}
```
错误：`400` 校验失败 / `第 N 天超出行程范围（当前共 M 天）`；`401`；`404`（手动新增会写入撤销历史）

### 3.11 GET /api/trips/{id}/budget — 预算执行对账
鉴权：登录 ｜ 源码：`TripController#budget:100` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":{"tripId":1,"tripName":"杭州三日","budget":5000.00,"planned":4820.00,"actual":365.00,"remaining":4635.00,"variance":-4455.00,"executionRate":0.073,"overBudget":false,"planOverBudget":false,"categories":[{"category":"住宿","planned":1800.00,"actual":0.00}],"daily":[{"day":1,"planned":1600.00,"actual":120.00,"variance":-1480.00}]}}
```
错误：`401`；`404`。`variance = actual - planned`（正数=比计划多花）；`daily[].day=0` 表示行程日期之外的支出。

### 3.12 GET /api/trips/{id}/guidebook.pdf — 路书导出
鉴权：登录 ｜ 源码：`TripController#guidebook` ｜ 参数：路径 `id`
响应：`application/pdf` 字节流，`Content-Disposition: inline; filename="trip-1-guidebook.pdf"`（浏览器内联预览，可另存）。
错误：`401`；`404`

### 3.13 GET /api/trips/{id}/review — 旅行回顾（P1）
鉴权：登录 ｜ 源码：`TripController#review` ｜ 参数：路径 `id`；只读聚合，不改变行程
```json
{"code":0,"message":"success","data":{
  "tripId":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-10","endDate":"2026-11-12",
  "totalDays":3,"phase":"PAST","totalItems":9,"completedItems":6,"skippedItems":1,"inProgressItems":0,"pendingItems":2,
  "completionRate":0.67,"plannedCost":4820.00,"actualCost":365.00,"budget":5000.00,"variance":-4455.00,"overBudget":false,
  "plannedPlaces":[{"itemId":11,"dayIndex":1,"itemType":"attraction","title":"西湖断桥","status":"COMPLETED","plannedAt":"2026-11-10T09:00:00","visitedAt":"2026-11-10T10:20:00","latitude":30.259,"longitude":120.148}],
  "visitedPlaces":[{"itemId":11,"dayIndex":1,"itemType":"attraction","title":"西湖断桥","status":"COMPLETED","plannedAt":"2026-11-10T09:00:00","visitedAt":"2026-11-10T10:20:00","latitude":30.259,"longitude":120.148}],"actualRoute":[{"itemId":11,"title":"西湖断桥","visitedAt":"2026-11-10T10:20:00","latitude":30.259,"longitude":120.148}],
  "photoCount":3,"photos":[],"events":[{"id":8,"itemId":11,"type":"CHECK_IN","source":"user","summary":"已完成：西湖断桥","occurredAt":"2026-11-10T10:20:00"}],
  "modifications":[{"id":12,"operationType":"MANUAL_EDIT","reason":"行程项状态：西湖断桥 → COMPLETED","at":"2026-11-10T10:20:00"}],
  "memoCount":2,"scheduleCount":1,"aiSummary":"这趟杭州行完成率 67%…"}}
```
字段与口径（`TravelReviewDtos.TripReview` / `TripReviewService`）：

| 字段 | 说明 |
| --- | --- |
| `totalItems` / `completedItems` / `skippedItems` / `inProgressItems` / `pendingItems` | 按 `status` 计数；`SKIPPED` 单列，**不计入完成** |
| `completionRate` | `completedItems / totalItems`（0..1；无条目为 0） |
| `plannedCost` | `itinerary_item.cost` 合计（计划成本） |
| `actualCost` | `expense` 合计（实际记账），与计划成本互不覆盖 |
| `variance` | `actualCost - plannedCost`；`overBudget` 为实际支出是否超 `budget` |
| `plannedPlaces` | 计划地点（有坐标的条目，含未到访） |
| `visitedPlaces` | 实际到访：**只认 `visitedAt` 非空**，与计划地点分开 |
| `actualRoute` | 按 `visitedAt` 排序的实际路线点，无坐标条目跳过 |
| `photoCount` / `photos` | 照片总数与最多 12 张预览 |
| `events` / `modifications` | 最近事件（默认 20 条）与最近修改历史（最多 20 条） |
| `memoCount` / `scheduleCount` | 按 `tripId` 关联的备忘/日程数量 |
| `aiSummary` | LLM 可用时按真实数据生成；不可用为确定性规则文案，不编造事实 |

错误：`401`；`404`（他人行程同样 404）

### 3.14 GET /api/trips/{id}/events — 旅行事件流（P1）
鉴权：登录 ｜ 源码：`TripController#events` ｜ 参数：路径 `id`、`limit`（默认 20，归一化上限 200）
```json
{"code":0,"message":"success","data":[{"id":8,"itemId":11,"type":"CHECK_IN","source":"user","summary":"已完成：西湖断桥","occurredAt":"2026-11-20T10:20:00"}]}
```
`type`：`WEATHER`（天气突变）/ `CONGESTION`（拥堵）/ `CLOSURE`（闭馆）/ `CHECK_IN`（用户打卡）/
`AGENT_ADJUST`（Agent 调整）/ `MANUAL_EDIT`（手工修改）。`source`：`weather` / `amap` / `user` / `agent` / `system`。
当前实际写入 `WEATHER` / `AGENT_ADJUST` / `CHECK_IN` / `MANUAL_EDIT`；`CONGESTION` / `CLOSURE` 为预留类型，尚无自动写入方。
事件表只追加，按 `occurredAt` 倒序；错误：`401`；`404`。

---

## 4. 行程项（3 个）

### 4.1 PUT /api/itinerary/{id} — 修改行程项
鉴权：登录 ｜ 源码：`ItineraryController#update:27`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `title` | 否 | ≤200 字符 |
| `dayIndex` | 否 | 第几天 |
| `itemType` | 否 | 条目类型 |
| `startTime` / `endTime` | 否 | `HH:mm` |
| `cost` | 否 | ≥0 |
| `latitude` / `longitude` | 否 | 坐标 |
| `tags` / `description` | 否 | 标签数组 / 说明 |
```json
// 请求
{"title":"西湖断桥（清晨）","startTime":"08:30","cost":0}
// 响应
{"code":0,"message":"success","data":{"id":11,"tripId":1,"dayIndex":1,"itemType":"attraction","title":"西湖断桥（清晨）","startTime":"2026-11-20T08:30:00","endTime":"2026-11-20T11:00:00","latitude":30.259,"longitude":120.148,"cost":0.00,"tags":["室外","免费"],"description":"清晨人少"}}
```
错误：`400` 校验失败；`401`；`404` `行程条目不存在`；修改会进入撤销链

### 4.2 DELETE /api/itinerary/{id} — 删除行程项
鉴权：登录 ｜ 源码：`ItineraryController#delete` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404`；删除会进入撤销链

### 4.3 PUT /api/itinerary/{id}/status — 行程项状态推进（P1）
鉴权：登录 ｜ 源码：`ItineraryController#updateStatus` ｜ 参数：路径 `id`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 是 | `PENDING` / `IN_PROGRESS` / `COMPLETED` / `SKIPPED`，大小写不敏感 |
```json
// 请求
{"status":"COMPLETED"}
// 响应
{"code":0,"message":"success","data":{"id":11,"tripId":1,"dayIndex":1,"itemType":"attraction","title":"西湖断桥","startTime":"2026-11-20T09:00:00","endTime":"2026-11-20T11:00:00","status":"COMPLETED","startedAt":"2026-11-20T10:00:00","completedAt":"2026-11-20T10:20:00","visitedAt":"2026-11-20T10:20:00"}}
```
状态语义（`ItineraryService.updateStatus`）：

| 目标状态 | 写入的实际时间 | 事件 |
| --- | --- | --- |
| `PENDING`（重置） | 清空 `startedAt` / `completedAt` / `visitedAt` | 不记事件（避免把「撤销打卡」记成一次打卡） |
| `IN_PROGRESS` | `startedAt`（已有则保留），清空 `completedAt` / `visitedAt` | `MANUAL_EDIT` |
| `COMPLETED` | `startedAt`、`completedAt`、`visitedAt` 均写当前时间 | `CHECK_IN` |
| `SKIPPED` | `completedAt` 写当前时间；`visitedAt` 保持空 | `MANUAL_EDIT` |

状态变化走 `OperationHistoryService.withHistory`，与本次修改同事务进入撤销链；`before` 快照在变更前生成，
撤销可还原打卡状态。错误：`400` `未知的行程项状态: xxx`；`401`；`404` `行程条目不存在`。

---

## 5. Agent 对话、方案与追踪（12 个 JSON 接口 + 3 个 SSE 端点）

### 5.1 Agent 统一返回结构 `AgentResult`

`chat` / `confirm` / `cancel` / `undo` / `plans/{planId}` 的 `data` 均为 `AgentDtos.AgentResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `planId` | `String` | 待确认方案 ID（`NEED_CONFIRMATION` 时非空） |
| `tripId` | `Long` | 关联行程 ID |
| `message` | `String` | 可直接展示的回复文本 |
| `intent` | `String` | `PLAN_TRIP` / `MODIFY_ITINERARY` / `QUERY_BUDGET` / `RECORD_EXPENSE` / `RECORD_INVOICE` / `ADD_MEMO` / `ADD_SCHEDULE` / `CLEAR_SCHEDULE` / `CHITCHAT` / `REJECTED` / `ERROR` 等 |
| `status` | `String` | `COMPLETED` / `NEED_CONFIRMATION` / `FAILED` |
| `focus` | `Object` | `{type:itinerary\|map\|budget\|schedule, tripId, day, itemId, latitude, longitude}`；批量清空日程时 `type=schedule`、`tripId` 为空；发票报销确认后 `type=budget`（`tripId` 是被关联的行程，未关联为 `null`） |
| `diff` | `Object` | `{changes:[{type:ADD\|REMOVE\|MODIFY,itemId,itemType,title,detail,cost,dayIndex,startTime}], budgetBefore, budgetAfter}`；日程操作用 `type=REMOVE` + `itemType=schedule`（`itemId` 是 `schedule_item.id`）；发票报销用 `type=ADD` + `itemType=expense`（`cost` 恒为 `null`、`startTime` 是账单日期） |
| `budget` | `Object` | `{total, previousTotal, difference, breakdown:{分类:金额}}` |
| `confirmation` | `Object` | `{planId, summary, riskLevel:LOW\|MEDIUM\|HIGH, contentHash, requiresAcknowledgement}`；`contentHash` 是方案内容的 SHA-256（V2.2 FR-08），确认时回传即可绑定「用户看到的内容」；`requiresAcknowledgement`（FR-06）为 `true` 时确认请求必须带 `acknowledgeIssues=true` |
| `warnings` | `String[]` | 反思/风险提示（旧契约；有 `review` 时它的 `issues` 是同一批问题的结构化版本） |
| `review` | `Object` | **V2.2 FR-06 审核结论**：`{passed, blocked, attempts, issues:[{type,severity,message}], repairs:[String]}`；`blocked=true` 表示有限重规划后仍有未解决 HIGH 问题，确认必须显式接受风险（见 §5.5）；`attempts`/`repairs` 是引擎已自动完成的修复动作 |
| `missingSlots` | `String[]` | **V2.2 FR-01 需求追问**：非空（`destination`/`days`/`partySize`）表示本轮没有规划，而是在合并追问；缺省/空表示正常规划。追问轮次的会话消息带 `intent=PLAN_TRIP_NEED_INFO`，下一轮短回答会续槽 |
| `execution` | `Object` | 仅 `confirm` 成功路径：`{executionId, planId, tripId, status:SUCCEEDED\|PARTIAL_FAILED\|FAILED, modules:[...]}`（V2.2 FR-09，见 §5.14） |
| `citations` | `Object[]` | **P1 知识依据**：`[{documentId, version, chunkId, score, source, title, snippet, label}]`；规划/修改路径可能为空数组，前端不得编造。与 `/api/knowledge/search` 同构 |

**`status=FAILED` 的语义**：`code` 仍为 `0`（HTTP 200），失败原因在 `data.message`，不产生 `planId` / `diff`。
一类典型场景是**依赖天气的修改拿不到真实预报**：返回 `status=FAILED`（内部业务码 503），
消息形如「没拿到 上海 11月24日 的真实天气（网络不可达）……本次没有生成修改方案」，
不会用模拟天气改行程，也不会写待确认方案。

`review.issues[].type`：`BUDGET_OVERRUN`（预算超限）/ `TRANSFER_TIME`（转场时间不够）/ `WEATHER_OUTDOOR`（雨天排户外）为
`HIGH`（硬约束，触发重规划）；`TIME_CONFLICT`（时间重叠）为 `MEDIUM`。
`WEATHER_OUTDOOR` 只在拿到真实预报且大概率下雨时产生；模拟天气（仅 `WEATHER_SIMULATION=true` 的测试/离线演示）下为 `MEDIUM`。
拿不到真实天气时**不会产生该 issue**，而是在 `warnings` 里写「未获取真实天气（原因），本次未做雨天/户外校验」——
不凭不存在的事实判 HIGH，也不假装校验过。

### 5.2 POST /api/agent/chat — 同步对话
鉴权：登录 ｜ 源码：`AgentController#chat:39`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | ≤500 字符 |
| `tripId` | 否 | 当前行程 ID |
| `currentDay` | 否 | 当前查看的第几天 |
| `selectedElement` | 否 | `{type, id, title}`：用户选中的元素，用于消解指代 |
| `mapCenter` | 否 | `{lat, lng}`：当前地图中心 |
| `conversationId` | 否 | 前端生成的会话标识，`[A-Za-z0-9_-]` 且 ≤64；为空表示单轮 |
```json
// 请求
{"message":"帮我规划杭州三日游，2 人，预算 5000","conversationId":"c-1a2b3c"}
// 响应（节选）
{"code":0,"message":"success","data":{"planId":"plan-8f3c","tripId":null,"message":"已为你规划「杭州3日游」（11月20日出发，2 人）：…","intent":"PLAN_TRIP","status":"NEED_CONFIRMATION","focus":{"type":"itinerary","tripId":null,"day":1,"itemId":null,"latitude":null,"longitude":null},"diff":{"changes":[{"type":"ADD","itemId":null,"itemType":"attraction","title":"西湖断桥","detail":null,"cost":0.00,"dayIndex":1,"startTime":"09:00"}],"budgetBefore":null,"budgetAfter":4820.00},"budget":{"total":4820.00,"previousTotal":null,"difference":null,"breakdown":{"住宿":1800.00}},"confirmation":{"planId":"plan-8f3c","summary":"3 天 8 个安排","riskLevel":"MEDIUM"},"warnings":[]}}
// 缺信息时（FR-01）：不规划，只追问
{"code":0,"message":"success","data":{"message":"开始规划前，还需要确认：\n· 去哪里？\n· 玩几天？\n· 几个人一起（儿童也算人数）？\n预算和出发日期也可以顺手说；不说的话我按 ¥5000、明天出发估算。","intent":"PLAN_TRIP","status":"COMPLETED","missingSlots":["destination","days","partySize"]}}
```
错误：`400` 校验失败；`401`；`429` 限流

**批量清空日程（`CLEAR_SCHEDULE`）走确认门**：消息里出现「清空类动词 + 日程」，或「清空类动词 + 所有/全部 + 提醒」
且带日期范围时（如「下周一清空所有日程」「删掉明天的所有提醒」；只说「提醒」不算日程对象，
「提醒我明天清空购物车」仍是备忘），本轮不删任何数据，而是返回 `status=NEED_CONFIRMATION` 的方案——
`diff.changes` 是范围内全部日程的 `REMOVE` 项（`itemType=schedule`），`confirmation.contentHash` 与
`plan_revision` 一并给出。确认提交（§5.5）在单事务内删除日程并写一条 `domain=SCHEDULE` 的操作历史，
可用 §5.7 的不带 `tripId` 撤销还原。语义边界：
- **范围必填**：没给日期（「清空所有日程」）只追问「要清空哪段时间的日程？」，不生成方案；
  单次最多 31 天，超出按前 31 天截断，`confirmation.summary` 写实际范围。
- **范围为空**：返回 `status=COMPLETED` +「这段时间没有日程，无需清空。」，不生成方案。
- **范围含已完成日程**：一并清空（`done` 也属于这段时间的日程），历史里保留原状态。
- **范围指纹**：`plan_revision` 覆盖「该用户该范围全部日程」；生成方案后另一个标签页新增/删除/改了范围内日程，
  确认会返回 200 + `status=FAILED`，消息「日程在你确认前已经发生变化…这份方案已失效，请重新发起。」，一条都不删。

**发票报销（`RECORD_INVOICE`）走确认门（V32）**：先把发票当照片上传（§7.1 的 `kind=INVOICE`），再说「报销这张发票」。

- 取该用户**最新一张** INVOICE 照片做 OCR（默认关闭，见 [能力边界](capability-boundaries.md) §2）。
  `diff.changes` 里是一条 `type=ADD` / `itemType=expense` 的记账项：`title` 形如「记账：餐饮 ¥128.5 · 商户名」，
  `detail` 形如「关联：行程 上海差旅（上海）、日程 09:00 客户汇报；识别原文：＜最多 60 字摘要＞」或
  「关联：未关联（没有匹配的行程或日程）」，
  `cost` 为 `null`、`budgetBefore/After` 都是 `0`（账单不进行程预算）；`message` 与 `confirmation.summary` 复述识别结果。
- 确认提交（§5.5）后单事务落一条 `expense`：金额 / 日期（发票开票日期，识别不出回落当天）/ 类目 / 备注（商户）
  来自识别，`payment_method` 默认 `现金`；`trip_id` / `schedule_item_id` 来自规则匹配
  （同日期未完成日程 + 同城且覆盖该日期的行程）。方案没有行程范围，`plan_revision` 为 `null`，
  确认门只做 `contentHash` 单指纹校验；确认结果 `tripId` 为 `null`（记账不产生行程同步）。
- 未配置 / 识别失败 / 没识别出金额：`status=COMPLETED` + 明确原因（形如「OCR 发票识别未配置（…）」「发票识别失败（…）」），
  **`confirmation` 为空、不生成方案、不写账单**——绝不返回模拟识别结果。

票据只读会话（§1.3）对 `RECORD_INVOICE` / `CLEAR_SCHEDULE` 的行为与规划类一致：只产生待确认方案、不能确认
（`/api/agent/confirm` 不接受票据），确认需在网页端登录操作。

### 5.3 GET /api/agent/stream — SSE 流式对话（EventSource）
鉴权：登录（**EventSource 用 `?ticket=` 票据**，不能用 `Authorization` 头）｜ 源码：`AgentController#stream:69`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | 用户消息 |
| `ticket` | 否* | 短期只读票据，**用途必须是 `agent-stream`**（见 §1.3）；浏览器 EventSource 必须提供 |
| `tripId` / `currentDay` | 否 | 同 §5.2 |
| `conversationId` | 否 | 非法值（超长/非法字符）按单轮处理 |
| `selType` / `selId` / `selTitle` | 否 | 选中元素三元组（`selType` 存在才算） |
| `lat` / `lng` | 否 | 地图中心，需同时提供 |
响应 `text/event-stream`，超时 180 秒。**每个事件都带 `id: <runId>:<seq>`**（`seq` 为 run 内从 1 单调递增；
`run` 事件之前、runId 尚未产生的极早期帧可能没有 id，这类帧不可续传）。事件名与 `data`：

```
id: 8f3c0d2e-…:1
event:start
data:{"sessionId":"3f1c2e9a-7c41-4c1e-9f5a-2b8e6d0a1c33"}

id: 8f3c0d2e-…:2
event:run
data:{"runId":"8f3c0d2e-…"}

id: 8f3c0d2e-…:3
event:intent
data:{"intent":"PLAN_TRIP","target":"NONE","constraints":{},"toolName":null,"toolArgs":null}

id: 8f3c0d2e-…:4
event:planning
data:{"message":"正在为你规划行程…"}

id: 8f3c0d2e-…:9
event:complete
data:{"planId":"plan-8f3c","tripId":null,"message":"…","intent":"PLAN_TRIP","status":"NEED_CONFIRMATION"}
```
其余事件：`run`（V2.2 FR-10，紧随 `start`，`{runId}` 可查 §5.13 的完整追踪）、`tool_call`（`{tool,args}`）、
`tool_result`（`{tool,result}`）、`plan_update`（`{diff,budget}`）、`focus`、
`confirmation`（含 `contentHash`，确认时回传见 §5.5）、
`rag_result`（**P1**：`{sources:[标题], citations:[{documentId,version,chunkId,score,source,title,snippet,label}]}`；
知识问答与规划期检索都会发，只有命中才发）、`error`（`{message}`）、
`replan`（**V2.2 FR-06**：审核发现 HIGH 级问题、进入有限重规划时发一次，`data={attempt, issues:[摘要]}`，
最多 2 次；修复动作随最终 `complete.review.repairs` 下发）。

**任务图事件（批次 15 / FR-02/FR-04，纯增量）**：

| 事件 | data | 时机 |
| --- | --- | --- |
| `delegate` | `{taskId, receiver, taskType, dependsOn[], attempt}` | 每次任务真正启动前（意图/规划/预览/审核/重规划/工具） |
| `recover` | `{taskId, receiver, taskType, replacesTaskId, attempt, reason}` | 回收重派（取代旧任务）时 |
| `conflict` | `{receiver, taskType, missingDeps[], reason}` | 前置任务未完成、派发被拒绝时 |

旧前端不认识这三个事件可安全忽略（事件名与既有语义不变）；新前端在过程时间线里渲染为
「委派 / 重派 / 冲突」三类行。

**断线续传（P0）**：浏览器 `EventSource` 自动重连时会把最后收到的帧 id 写进 `Last-Event-ID` 头。
`GET` / `POST /api/agent/stream` 只要收到这个头就**续传原 run，不新开规划**；头部必须是
`<runId>:<seq>`，格式非法返回 **400**，run 不存在或不属于当前用户返回 **404**。
续传失败/超时不会静默重规划。独立端点与完整语义见 §5.13.1。

错误：`401`（票据无效/过期，含用途不匹配）；线程池满时推送 `event:error` 后关闭。

> 响应头带 `Referrer-Policy: no-referrer`，避免 `?ticket=` 随 Referer 外泄。
> 仅凭票据认证（不带 Cookie）的请求是**只读会话**：记账/备忘/日程三类写意图会被拒绝，`message` 见 §1.3，规划仍可用。

### 5.4 POST /api/agent/stream — SSE 流式对话（结构化表单入口）
鉴权：登录 ｜ 源码：`AgentController#streamPost:98`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | ≤20000 字符（可内联参考攻略正文） |
| `tripId` / `currentDay` | 否 | 同 §5.2 |
| `selectedElement` / `mapCenter` | 否 | 同 §5.2 |
| `conversationId` | 否 | `[A-Za-z0-9_-]` 且 ≤64 |
```json
// 请求
{"message":"按这份攻略规划杭州三日：Day1 西湖…","tripId":null,"currentDay":null,"conversationId":"c-1a2b3c"}
// 响应：与 §5.3 相同的 text/event-stream
```
错误：`400` 校验失败 / `Last-Event-ID` 格式非法；`401`；`429`。
带 `Last-Event-ID` 时与 §5.3 相同，转续传而不是新开 run（`404` 为 run 不存在或不属于当前用户）。

### 5.5 POST /api/agent/confirm — 确认提交方案
鉴权：登录 ｜ 源码：`AgentController#confirm:223`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `planId` | 是 | 方案 ID（来自 `confirmation.planId`） |
| `contentHash` | 否 | 方案内容指纹（`confirmation.contentHash`，≤64）；**V2.2 FR-08**：携带时校验「确认的就是看到的内容」，不传走既有版本校验（向后兼容） |
| `acknowledgeIssues` | 否 | **V2.2 FR-06**：`confirmation.requiresAcknowledgement=true`（即 `review.blocked=true`）时必须为 `true`，表示用户已在弹窗中明确接受未解决的高风险问题；缺省/false 时行为与旧前端一致 |
```json
// 请求
{"planId":"plan-8f3c","contentHash":"a3f1…9c2e"}
// 响应（方案已过期时为 200 + status=FAILED）
{"code":0,"message":"success","data":{"planId":"plan-8f3c","tripId":1,"message":"已提交，行程已更新。\n✅ 已同步：日程时间块、预算快照、足迹地图。","intent":null,"status":"COMPLETED","focus":null,"diff":null,"budget":null,"confirmation":null,"warnings":null,"execution":{"executionId":"e-77b1","planId":"plan-8f3c","tripId":1,"status":"SUCCEEDED","modules":[{"module":"calendar","status":"SUCCEEDED","detail":"3 天行程已同步为日程时间块（本次新建 3 条）","idempotencyKey":"plan:plan-8f3c:module:calendar","retryCount":null,"error":null}]}}}
```
错误：`400` `planId 不能为空`；`401`。确认前行程被改动（`planRevision` 不一致）或方案内容被改（`contentHash` 不一致）时返回 200、`status=FAILED` 并提示方案失效；方案不存在/过期同理，不抛 404。批量清空日程的方案没有行程，`planRevision` 覆盖的是「该用户该范围内全部日程」，范围内的新增/删除/改时间同样让方案失效（消息里说明是日程变化）。

**审核门的显式接受（V2.2 FR-06）**：方案 `review.blocked=true`（有限重规划后仍有 HIGH 级问题）时，
不带 `acknowledgeIssues=true` 的确认会被拒绝——返回 200、`status=FAILED`、`data.review` 带未解决问题，
消息提示「该方案仍有未解决的高风险问题（…），请勾选『我已了解风险』后再提交」。
被拒时方案**不被消费**（`PendingPlanStore.release`），用户可先调整需求重新规划，也可以带
`acknowledgeIssues=true` 用同一 `planId` 重试。

**租约与并发语义（P0）**：确认请求先抢 `pending_plan` 的租约（`PENDING→CONFIRMING` + 一次性令牌），
抢不到返回 200、`status=FAILED`、消息「该方案正在处理中或已被确认/取消…」。
提交在**单事务**内完成：行程/条目/操作历史 + 3 条 `plan_outbox(PENDING)` + 删除仍持有本次令牌的方案行（栅栏）。
若租约在提交期间被清扫或被其它请求接管，栅栏影响 0 行 → 整体回滚，返回 200、`status=FAILED`、
消息「该方案的处理租约已失效（处理超时或已由其它请求接管），本次未做任何提交…」——**不会出现部分落库**。
提交失败（非租约失效）按原语义保留方案行，用户可带同一 `planId` 重试。

**确认后的跨模块同步（V2.2 FR-09 / P0 Outbox）**：提交事务内写入 `calendar` / `budget` / `footprint`
三条待办；确认请求会同步投递一次，进程崩溃或投递失败由定时投递器补投（至少一次、幂等消费）。
模块级失败**不回滚**已保存的行程，响应 `execution.status` 为 `PARTIAL_FAILED` / `FAILED`，
失败模块可用 §5.14 单独重试（成功项不重复创建）。口径红线：`budget` 只记计划快照（估算不会自动变成已消费），
`footprint` 只标计划态（不会把未来行程记为已到访）。

### 5.6 POST /api/agent/cancel — 取消方案
鉴权：登录 ｜ 源码：`AgentController#cancel:124` ｜ 参数：`planId`（必填）
```json
// 请求
{"planId":"plan-8f3c"}
// 响应
{"code":0,"message":"success","data":{"planId":"plan-8f3c","tripId":null,"message":"已取消该方案，行程未做任何修改。","intent":null,"status":"COMPLETED","focus":null,"diff":null,"budget":null,"confirmation":null,"warnings":null}}
```
错误：`400`；`401`（cancel 幂等，方案不存在同样返回成功）

### 5.7 POST /api/agent/undo — 撤销上一次修改
鉴权：登录 ｜ 源码：`AgentController#undo:131`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `tripId` | 否 | 行程 ID；**不传或传 `null` = 撤销最近一次「批量清空日程」**（日程不挂在行程上，按当前用户 + `SCHEDULE` 域取最近一条 ACTIVE 历史） |
```json
// 请求（行程撤销）
{"tripId":1}
// 响应
{"code":0,"message":"success","data":{"planId":null,"tripId":1,"message":"已撤销上一步操作（还原 8 项安排）","intent":null,"status":"COMPLETED","focus":null,"diff":null,"budget":null,"confirmation":null,"warnings":null}}

// 请求（撤销日程清空）
{}
// 响应（tripId 为 null）
{"code":0,"message":"success","data":{"planId":null,"tripId":null,"message":"已撤销上一步日程清空（还原 3 项日程）","intent":null,"status":"COMPLETED","focus":null,"diff":null,"budget":null,"confirmation":null,"warnings":null}}
```
错误：`400` `没有可撤销的操作` / `撤销失败: …`；`401`；`404`。
撤销「新建行程」会删除整个行程。行程撤销与日程撤销是两条互不覆盖的链：
带 `tripId` 只查该行程的 `ITINERARY` 域历史，不带 `tripId` 只查当前用户的 `SCHEDULE` 域历史
（没有可撤销记录时 `404` `没有可撤销的日程清空`）。

### 5.8 GET /api/agent/plans/{planId} — 取回待确认方案
鉴权：登录 ｜ 源码：`AgentController#pending:139` ｜ 参数：路径 `planId`
```json
{"code":0,"message":"success","data":{"planId":"plan-8f3c","tripId":null,"message":"已为你生成杭州三日行程…","intent":"PLAN_TRIP","status":"NEED_CONFIRMATION","focus":null,"diff":null,"budget":null,"confirmation":{"planId":"plan-8f3c","summary":"3 天 8 个安排","riskLevel":"MEDIUM"},"warnings":[]}}
```
错误：`401`；`404` `待确认方案不存在或已过期，请重新发起`

### 5.9 POST /api/agent/conversation/clear — 清空会话
鉴权：登录 ｜ 源码：`AgentController#clearConversation:146`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `conversationId` | 是 | `[A-Za-z0-9_-]+`，≤64 字符 |
```json
// 请求
{"conversationId":"c-1a2b3c"}
// 响应
{"code":0,"message":"success","data":null}
```
错误：`400` 校验失败；`401`

### 5.10 PATCH /api/agent/conversations/{conversationId} — 重命名会话（V23）
鉴权：登录 ｜ 源码：`AgentController#renameConversation` ｜ 参数：路径 `conversationId`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `title` | 否 | ≤40 字符；**空串 / 只有空白 = 恢复自动标题**（回到「首条用户消息」的推导标题） |
```json
// 请求
{"title":"杭州周末"}
// 响应
{"code":0,"message":"success","data":{"conversationId":"c-1a2b3c","title":"杭州周末"}}
// 恢复自动标题的响应（title=null 表示已删掉覆盖行）
{"code":0,"message":"success","data":{"conversationId":"c-1a2b3c","title":null}}
```
只改展示标题，不动消息、不影响续聊。不属于当前用户的 `conversationId` 同样返回成功
（写入按 `user_id` 隔离，改不到别人的会话；也不提供 404，避免变成会话存在性探测接口）。
错误：`400` 标题超长；`401`

### 5.11 DELETE /api/agent/conversations/{conversationId} — 删除会话（V23）
鉴权：登录 ｜ 源码：`AgentController#deleteConversation` ｜ 参数：路径 `conversationId`
```json
{"code":0,"message":"success","data":null}
```
把该会话的消息与标题覆盖（`conversation_meta`）一起删，**不可恢复**。幂等：不存在的会话同样返回成功
（保留期清理可能已经删过）；非法 / 超长的 `conversationId` 清洗成「无此会话」，删不到任何数据。
错误：`401`

### 5.12 GET /api/agent/runs — 运行列表（V2.2 FR-10）
鉴权：登录 ｜ 源码：`AgentController#runs` ｜ 参数：`limit`（默认 20，上限 50）
```json
{"code":0,"message":"success","data":[{"runId":"8f3c…","tripId":null,"message":"帮我规划杭州三日游","intent":"PLAN_TRIP","status":"SUCCEEDED","error":null,"startedAt":"2026-11-18 12:03:11","endedAt":"2026-11-18 12:03:19","durationMs":8123}]}
```
`status`：`RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED`（`NEED_CONFIRMATION` 记为 `SUCCEEDED`——规划已完成，等待用户动作）。
错误：`401`

### 5.13 GET /api/agent/runs/{runId} — 运行明细（任务图 + 工具调用）
鉴权：登录 ｜ 源码：`AgentController#runDetail` ｜ 参数：路径 `runId`
```json
{"code":0,"message":"success","data":{
  "run":{"runId":"8f3c…","status":"SUCCEEDED","intent":"PLAN_TRIP","durationMs":8123,"error":null},
  "tasks":[{"taskId":"t1","receiver":"intent-agent","taskType":"INTENT","status":"SUCCEEDED","dependsOn":[],"attempt":0,"replacesTaskId":null,"summary":"识别意图: PLAN_TRIP","error":null,"durationMs":210},
           {"taskId":"t2","receiver":"planner-agent","taskType":"PLAN","status":"SUCCEEDED","dependsOn":["t1"],"attempt":0,"replacesTaskId":null,"summary":"生成 9 项操作","error":null,"durationMs":6200},
           {"taskId":"t3","receiver":"executor-agent","taskType":"PREVIEW","status":"SUCCEEDED","dependsOn":["t2"],"attempt":0,"replacesTaskId":null,"summary":"生成 Diff 预览：9 项变更","error":null,"durationMs":35},
           {"taskId":"t4","receiver":"reflect-agent","taskType":"REVIEW","status":"SUCCEEDED","dependsOn":["t2"],"attempt":0,"replacesTaskId":null,"summary":"审核通过","error":null,"durationMs":1820}],
  "toolCalls":[{"id":1,"taskId":"t2","toolName":"search_hotel","success":true,"errorCode":null,"latencyMs":12,"argsDigest":"{\"city\":\"杭州\"}","resultDigest":"[…]","startedAt":"2026-11-18 12:03:14"}],
  "spend":{"llmCalls":8,"toolCalls":12,"retries":0,"promptTokens":15230,"completionTokens":2411,"totalTokens":17641,"costUsd":0.0021,"exhausted":null}}}
```
`receiver`：`intent-agent` / `research-agent` / `planner-agent` / `executor-agent` / `reflect-agent` / `tool-invoker`；
`taskType`：`INTENT` / `RESEARCH` / `PLAN` / `PREVIEW` / `REVIEW` / `REPLAN` / `TOOL` / `COMMIT`；
`status`：`RUNNING` / `SUCCEEDED` / `FAILED` / `CONFLICT`（批次 15 新增：前置未完成、任务被拒绝启动）。

**任务图字段（批次 15 / FR-02/FR-04，纯增量）**：`dependsOn`（前置任务 id 列表）、`attempt`
（0 = 首次，>0 = 回收重派）、`replacesTaskId`（被本次重派替换的旧任务 id）。
一次规划的任务图形状：`INTENT → PLAN → {PREVIEW ‖ REVIEW} → (REPLAN → REVIEW)*`；
`PREVIEW` 与 `REVIEW` 并行执行（时间区间重叠，`agent_task.id` 顺序按声明顺序稳定）。
`CONFLICT` 行只作证据、没有执行体（`endedAt` 与 `startedAt` 同时写入）。

摘要字段为截断 JSON（不含密钥）。错误：`401`；`404` `运行记录不存在`（他人的 run 同样 404）

`spend`（第二阶段 / V29，纯增量）：本轮花费快照，来自 `agent_run_spend`。字段：
`llmCalls` / `toolCalls` / `retries`（真实调用数）、`promptTokens` / `completionTokens` / `totalTokens`（供应商 returned usage，不估算）、
`costUsd`（按配置单价折算，未配价目表恒为 0）、`exhausted`（触顶项 tag：`llm_calls`/`tool_calls`/`retries`/`tokens`/`cost`，未触顶为 `null`）。
未启用花费预算（`travel.agent.budget.spend.enabled=false`）或落库失败时该字段为 `null`——花费是补充信息，不影响运行详情主契约。

### 5.13.1 GET /api/agent/runs/{runId}/events — SSE 断线续传（P0，对 OpenAPI 隐藏）

鉴权：登录（EventSource 可用 `?ticket=`，用途必须是 `agent-stream`）｜ 源码：
`AgentController#runEvents`（`@Hidden`，不进入 `docs/api/openapi.json`）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `runId` | 是 | 路径参数；一次运行的 `runId`（来自 `run` 事件或帧 id） |
| `lastEventId` | 否 | 已收到的最后 `seq`；缺省时读 `Last-Event-ID` 头，两者都没有按 `0` 处理（补发全部持久化事件） |

响应 `text/event-stream`，帧格式与 §5.3 相同（`id: <runId>:<seq>`、`event: <事件名>`、`data:`）。
语义：

1. 先补发 `seq > lastEventId` 的持久化事件，按 `seq` 升序、不重复；单批最多 200 条。
2. 补发后重读 run 状态：**已终态** → 补完剩余事件后关闭连接。若补发集合里没有 `complete` / `cancelled`，
   合成一条 `complete`（`data.status=FAILED`、`message=运行已中断（服务重启或超时），请重试`、
   `runStatus` 为实际终态），避免客户端永远停在流式中。
3. **仍 RUNNING** → 每 500ms 轮询新事件，直到终态或最长 **120 秒**；超时发 `resume_timeout`
   （`data={runId,lastEventId,message}`）后关闭。
4. 续传连接断开不会取消原始运行；原始运行是否继续由 `/api/agent/stream` 的 `StreamLifecycle` 决定。

错误码：`400` `Last-Event-ID 格式非法，应为 <runId>:<seq>` / seq 为负数（查询参数 `lastEventId` 为负数时按 0 处理）；`401` 未登录或票据无效；
`404` 运行记录不存在或不属于当前用户（不泄露存在性）；`503` 服务器繁忙（`agentTaskExecutor` 线程池满）。
`resume_timeout` 是事件而非 HTTP 错误，连接以正常 SSE 关闭结束。

### 5.14 GET /api/agent/executions/{executionId} — 同步台账（V2.2 FR-09）
鉴权：登录 ｜ 源码：`AgentController#executionDetail` ｜ 参数：路径 `executionId`
```json
{"code":0,"message":"success","data":{"executionId":"e-77b1","planId":"plan-8f3c","tripId":1,"status":"PARTIAL_FAILED","modules":[
  {"module":"calendar","status":"SUCCEEDED","detail":"3 天行程已同步为日程时间块（本次新建 3 条）","idempotencyKey":"plan:plan-8f3c:module:calendar","retryCount":null,"error":null},
  {"module":"budget","status":"FAILED","detail":null,"idempotencyKey":"plan:plan-8f3c:module:budget","retryCount":0,"error":"行程不存在"}]}}
```
`module`：`calendar`（日程时间块）/ `budget`（计划预算快照）/ `footprint`（足迹计划标记）。
错误：`401`；`404` `执行记录不存在`

### 5.15 POST /api/agent/executions/{executionId}/retry — 重试失败模块
鉴权：登录 ｜ 源码：`AgentController#executionRetry` ｜ 参数：路径 `executionId`（无请求体）
```json
{"code":0,"message":"success","data":{"executionId":"e-77b1","status":"SUCCEEDED","modules":[…]}}
```
只重试 `FAILED` 模块，`SUCCEEDED` 模块原样保留（幂等键保证不重复创建）；`retryCount` 递增。
错误：`400` `该执行记录没有可重试的模块`；`401`；`404`

### 5.16 目的地城市名匹配（`util/CityNames`）

规划链路判断「目的地是否在目录内」统一走 `util/CityNames`（纯静态工具类，`OnlineCityService.matchesCity` 的唯一实现）：

- **包含关系且较短一方长度 ≥ 2 才算命中**；完全相等直接命中。归一化会剥掉「市 / 地区 / 特别行政区 / 自治州」后缀。
- **单字永不命中**：「阳」不再命中「洛阳」，「州」不再命中「杭州」，「北」不再命中「北京」。
- 目录内 4 城（上海 / 北京 / 杭州 / 石家庄）支持**非首位单字笔误**容错（`杭洲` → 杭州、`石家庒` → 石家庄）；
  **首位笔误一律不兜底**（`广州` / `苏州` / `南京` 不会被判成杭州 / 北京），继续走「不支持该目的地」兜底。
- 在线地理编码（Nominatim / 高德）返回的城市名会与查询城市名比对：既不 `matches` 也不是「非首位单笔误」时按失败处理并打 `WARN`，
  避免静默采用异城坐标。

---

## 6. 记账（4 个）

### 6.1 GET /api/trips/{tripId}/expenses — 行程支出列表
鉴权：登录 ｜ 源码：`ExpenseController#list:30` ｜ 参数：路径 `tripId` + `limit`（默认 20，上限 200）、`offset`（默认 0）；响应带 `pagination`（见 §1.6）
```json
{"code":0,"message":"success","data":[{"id":31,"tripId":1,"tripName":null,"category":"餐饮","amount":45.00,"note":"午餐","paymentMethod":"微信支付","spentAt":"2026-11-08","createdAt":"2026-11-08 12:30"}]}
```
错误：`401`；`404` `行程不存在或无权访问`

### 6.2 POST /api/trips/{tripId}/expenses — 记一笔
鉴权：登录 ｜ 源码：`ExpenseController#add:41`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `amount` | 是 | `BigDecimal` ≥0.01 |
| `category` | 否 | `餐饮`/`交通`/`住宿`/`门票`/`购物`/`其他`，非法值归 `其他`，缺省 `其他` |
| `note` | 否 | ≤500 字符 |
| `spentAt` | 否 | `yyyy-MM-dd`，缺省今天 |
| `paymentMethod` | 否 | `微信支付`/`支付宝`/`现金`/`银行卡`，非法值归 `现金`，缺省 `现金` |
```json
// 请求
{"category":"餐饮","amount":45,"note":"午餐","spentAt":"2026-11-08","paymentMethod":"微信支付"}
// 响应
{"code":0,"message":"success","data":{"id":31,"tripId":1,"tripName":null,"category":"餐饮","amount":45.00,"note":"午餐","paymentMethod":"微信支付","spentAt":"2026-11-08","createdAt":"2026-11-08 12:30"}}
```
错误：`400` 金额非法 / 日期格式错误；`401`；`404`

> V30 起账单可以不挂行程：本接口固定挂在路径行程上，没有当前行程的通用记账走 Agent 对话（`expense.trip_id` 为空，见 [能力边界 §1.1](capability-boundaries.md#11-记账口径v30-起账单可以没有行程)），REST 契约不变。

### 6.3 GET /api/expenses/overview — 跨行程记账总览
鉴权：登录 ｜ 源码：`ExpenseController#overview:55` ｜ 参数：无
```json
{"code":0,"message":"success","data":{"totalSpent":3560.00,"monthSpent":1240.00,"count":18,"byPaymentMethod":{"微信支付":2200.00,"现金":1360.00},"byCategory":{"餐饮":800.00,"住宿":2400.00,"其他":360.00},"byTrip":[{"tripId":1,"tripName":"杭州三日","total":2400.00}],"recent":[{"id":31,"tripId":1,"tripName":"杭州三日","category":"餐饮","amount":45.00,"note":"午餐","paymentMethod":"微信支付","spentAt":"2026-11-08","createdAt":"2026-11-08 12:30"}]}}
```
错误：`401`。`recent` 最多 8 条；已删除行程的支出归入 `tripId=0` / `已删除的行程`，未关联行程的支出归入 `tripId=-1` / `未关联行程`（V30 起，两组不合并）。第九批起分类 / 支付方式 / 行程维度改为数据库 `GROUP BY` 聚合、`recent` 单独查询，**响应 JSON 结构不变**。

### 6.4 DELETE /api/expenses/{id} — 删除支出
鉴权：登录 ｜ 源码：`ExpenseController#delete:61` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404` `支出记录不存在`（未关联行程的账单按 `user_id` 校验归属，不要求行程存在）

---

## 7. 照片（4 个）

### 7.1 POST /api/trips/{tripId}/photos — 上传照片
鉴权：登录 ｜ 源码：`PhotoController#upload:27` ｜ Content-Type：`multipart/form-data`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | 图片文件，单张 ≤10MB；扩展名或 MIME 需在 jpg/jpeg/png/webp/gif/bmp/heic/heif 白名单内，最终以文件头识别为准（svg 拒绝） |
| `kind` | 否 | **V32** 图片用途：`PHOTO`（缺省）/ `INVOICE`（发票，供「报销这张发票」取最新一张识别）；大小写不敏感，其它值 `400` |
```json
// 响应
{"code":0,"message":"success","data":{"id":21,"tripId":1,"originalName":"xihu.jpg","contentType":"image/jpeg","sizeBytes":204800,"createdAt":"2026-11-08T14:20:00","kind":"PHOTO","url":"/api/photos/21/raw"}}
```
错误：`400` `请选择要上传的照片` / `单张照片不能超过 10MB` / 张数或容量超限 / 格式不支持 / `文件内容不是可识别的图片，请重新选择` / `不支持的图片用途`；`401`；`404`；`429`

### 7.2 GET /api/trips/{tripId}/photos — 照片列表
鉴权：登录 ｜ 源码：`PhotoController#list:37` ｜ 参数：路径 `tripId`、`limit`、`offset`
```json
{"code":0,"message":"success","data":[{"id":21,"tripId":1,"originalName":"xihu.jpg","contentType":"image/jpeg","sizeBytes":204800,"createdAt":"2026-11-08T14:20:00","kind":"PHOTO","url":"/api/photos/21/raw"}]}
```
错误：`401`；`404`。`kind` 对 V32 之前上传的存量行回落为 `PHOTO`（不会返回 `null`）。

### 7.3 GET /api/photos/{id}/raw — 读取原图
鉴权：登录（`<img>` 用 `?ticket=` 票据，**用途必须是 `media-read`**，见 §1.3）｜ 源码：`PhotoController#raw:54` ｜ 参数：路径 `id`
响应：图片字节流，`Content-Type` 由文件头推导；带 `X-Content-Type-Options: nosniff`、`Content-Security-Policy: default-src 'none'; sandbox`、`Cache-Control: private, no-store`、`Referrer-Policy: no-referrer`。
错误：`401`；`404` `照片不存在` / `照片文件不存在（可能已被清理）`

### 7.4 DELETE /api/photos/{id} — 删除照片
鉴权：登录 ｜ 源码：`PhotoController#delete:76` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404`

---

## 8. 提醒（3 个）

### 8.1 GET /api/reminders — 提醒板
鉴权：登录 ｜ 源码：`ReminderController#board:22` ｜ 参数：`limit`（默认 20，上限 200）、`offset`（默认 0）；服务端按当前时间实时计算，`items` 分页、`count` 为提醒总数（见 §1.6）
```json
{"code":0,"message":"success","data":{"items":[{"type":"SCHEDULE","refId":5,"title":"产品评审会","detail":"2026-11-09 15:00 · 产品评审会","remindAt":"2026-11-09T14:30:00","eventAt":"2026-11-09T15:00:00","overdue":false,"minutesUntil":1458}],"count":1,"serverTime":"2026-11-08T19:38:04"}}
```
错误：`401`。规则：日程开始前 30 分钟出现、结束后 6 小时内仍可见（`overdue=true`）；行程出发前一天 09:00 至出发日 23:59；已忽略/已完成的不出现。

### 8.2 POST /api/reminders/schedule/{id}/dismiss — 忽略日程提醒
鉴权：登录 ｜ 源码：`ReminderController#dismissSchedule:28` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404` `日程不存在`

### 8.3 POST /api/reminders/trip/{id}/dismiss — 忽略出发提醒
鉴权：登录 ｜ 源码：`ReminderController#dismissTrip:35` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404` `行程不存在或无权访问`

---

## 9. 备忘（5 个）

### 9.1 GET /api/memos — 备忘列表
鉴权：登录 ｜ 源码：`MemoController#list:32` ｜ 参数：`limit`（默认 20，上限 200）、`offset`（默认 0），响应带 `pagination`（未完成在前，同状态按创建时间倒序）
```json
{"code":0,"message":"success","data":[{"id":12,"userId":2,"content":"明天办签证","done":false,"createdAt":"2026-11-08T10:20:00"}]}
```
错误：`401`

### 9.2 POST /api/memos — 新增备忘
鉴权：登录 ｜ 源码：`MemoController#add:41`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `content` | 是 | ≤500 字符 |
```json
// 请求
{"content":"明天办签证"}
// 响应
{"code":0,"message":"success","data":{"id":12,"userId":2,"content":"明天办签证","done":false,"createdAt":"2026-11-08T10:20:00"}}
```
错误：`400` 校验失败；`401`

### 9.3 POST /api/memos/{id}/toggle — 切换完成状态
鉴权：登录 ｜ 源码：`MemoController#toggle:47` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":{"id":12,"userId":2,"content":"明天办签证","done":true,"createdAt":"2026-11-08T10:20:00"}}
```
错误：`401`；`404` `备忘不存在`

### 9.4 DELETE /api/memos/{id} — 删除备忘
鉴权：登录 ｜ 源码：`MemoController#delete:53` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404`

### 9.5 GET /api/memos/open-count — 未完成备忘数量
鉴权：登录 ｜ 源码：`MemoController#openCount:60` ｜ 参数：无
```json
{"code":0,"message":"success","data":{"open":3}}
```
错误：`401`

---

## 10. 日程（4 个）

### 10.1 GET /api/schedule — 日程列表
鉴权：登录 ｜ 源码：`ScheduleController#list:39` ｜ 参数：`limit`、`offset`（按日期、时间升序）
```json
{"code":0,"message":"success","data":[{"id":5,"userId":2,"title":"产品评审会","date":"2026-11-09","time":"15:00:00","note":"会议室 A","done":false,"recurrence":"NONE","repeatUntil":null,"nextOccurrence":null,"reminderDismissedAt":null,"createdAt":"2026-11-08T10:30:00"}]}
```
错误：`401`。`nextOccurrence` 为重复日程的下次发生日期（未完成时计算），非数据库字段。

### 10.2 POST /api/schedule — 新增日程
鉴权：登录 ｜ 源码：`ScheduleController#add:48`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `title` | 是 | ≤200 字符 |
| `date` | 否 | `yyyy-MM-dd`，缺省今天 |
| `time` | 否 | `HH:mm` 或 `HH:mm:ss`，缺省全天 |
| `note` | 否 | ≤500 字符 |
| `recurrence` | 否 | `NONE`（缺省）/ `DAILY` / `WEEKLY` / `MONTHLY` |
| `repeatUntil` | 否 | `yyyy-MM-dd`，重复结束日（含） |
```json
// 请求
{"title":"产品评审会","date":"2026-11-09","time":"15:00","note":"会议室 A","recurrence":"NONE"}
// 响应
{"code":0,"message":"success","data":{"id":5,"userId":2,"title":"产品评审会","date":"2026-11-09","time":"15:00:00","note":"会议室 A","done":false,"recurrence":"NONE","repeatUntil":null,"nextOccurrence":null,"reminderDismissedAt":null,"createdAt":"2026-11-08T10:30:00"}}
```
错误：`400` 校验失败 / `日程日期不是有效日期，请使用 yyyy-MM-dd` / `日程时间不是有效时间，请使用 HH:mm`；`401`

### 10.3 POST /api/schedule/{id}/toggle — 切换完成状态
鉴权：登录 ｜ 源码：`ScheduleController#toggle:59` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":{"id":5,"userId":2,"title":"产品评审会","date":"2026-11-09","time":"15:00:00","note":"会议室 A","done":true,"recurrence":"NONE","repeatUntil":null,"nextOccurrence":null,"reminderDismissedAt":null,"createdAt":"2026-11-08T10:30:00"}}
```
错误：`401`；`404` `日程不存在`

### 10.4 DELETE /api/schedule/{id} — 删除日程
鉴权：登录 ｜ 源码：`ScheduleController#delete:65` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404`

---

## 11. 旅行知识库（5 个）

RAG 文档接口在 P0 从原 `LearningController` 拆出，迁入 `TravelKnowledgeController`（`/api/knowledge`）。它只服务旅行知识管理与 Planner 研究，不再提供「学习产物」业务。

### 11.1 POST /api/knowledge/add — 旅行知识库录入
鉴权：登录 ｜ 源码：`TravelKnowledgeController#addKnowledge`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `content` | 是 | ≤20000 字符；按空行切块后向量化入库 |
| `title` | 否 | ≤200 字符，缺省 `未命名知识`；同用户同标题同城市复用同一文档并追加版本 |
```json
// 请求
{"title":"签证材料","content":"申根签证需要：护照、照片、行程单…\n\n保险需覆盖全程…"}
// 响应（versionNo 为本次写入的版本号；首次录入为 1）
{"code":0,"message":"success","data":{"documentId":7,"chunks":2,"versionNo":1}}
```
错误：`400` 校验失败；`401`。内容指纹未变时走 `SKIPPED`，`versionNo` 返回当前 ACTIVE 版本号。

### 11.2 POST /api/knowledge/search — 知识库检索
鉴权：登录 ｜ 源码：`TravelKnowledgeController#searchKnowledge`（返回分数最高的 5 条；检索范围 = 公共知识 + 本人个人知识，不含他人私有文档）
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `query` | 是 | ≤500 字符 |
```json
// 请求
{"query":"申根签证需要什么材料"}
// 响应（每条都可追溯到文档 / 版本 / 切块）
{"code":0,"message":"success","data":[{"documentId":7,"version":1,"chunkId":31,"title":"签证材料","snippet":"申根签证需要：护照、照片、行程单…","source":"用户知识库","score":0.83}]}
```
错误：`400` 校验失败；`401`

### 11.3 GET /api/knowledge/documents/{id}/versions — 版本列表
鉴权：登录 ｜ 源码：`TravelKnowledgeController#versions` ｜ 参数：路径 `id`（文档 ID）
```json
{"code":0,"message":"success","data":[{"versionNo":2,"status":"ACTIVE","fingerprint":"9f2c…","createdAt":"2026-11-08T15:00:00","active":true},{"versionNo":1,"status":"INACTIVE","fingerprint":"3a71…","createdAt":"2026-11-01T09:00:00","active":false}]}
```
错误：`401`；`404` `文档不存在`。按 `versionNo` 倒序；他人个人文档同样返回 `404`（不暴露存在性）。

### 11.4 GET /api/knowledge/documents/{id}/versions/{versionNo} — 版本详情
鉴权：登录 ｜ 源码：`TravelKnowledgeController#version` ｜ 参数：路径 `id`、`versionNo`
```json
{"code":0,"message":"success","data":{"versionNo":1,"status":"INACTIVE","fingerprint":"3a71…","createdAt":"2026-11-01T09:00:00","active":false,"content":"申根签证需要：护照、照片、行程单…"}}
```
错误：`401`；`404` `文档不存在` / `版本不存在`

### 11.5 POST /api/knowledge/documents/{id}/rollback/{versionNo} — 回滚到指定版本
鉴权：登录 ｜ 源码：`TravelKnowledgeController#rollback` ｜ 参数：路径 `id`、`versionNo`
```json
{"code":0,"message":"success","data":{"activeVersionNo":1}}
```
错误：`400` `公共知识库文档不支持回滚`；`401`；`404` `文档不存在` / `版本不存在`。成功后服务端立即重建向量索引（`RagService.reload`）。

**版本化语义与已知限制**（表 `rag_document_version`，迁移 `V10`）：

- 同一文档任意时刻**至多一条 `ACTIVE`**；内容指纹变化时旧版置 `INACTIVE` 并**追加**新版本，旧正文与旧知识块不再删除（可审计、可回滚）。
- 向量检索只装载「`version_id` 为空（历史块）」或「所属版本为 `ACTIVE`」的块。
- **回滚不改 `rag_document.content` / `fingerprint`**（它仍是「源同步游标」）：源文件（`rag/knowledge.json`）下次变更会再产生新版本，覆盖回滚结果。
- 历史块由每日任务按 `RAG_CHUNK_VERSION_RETENTION` 清理，不会无限累积。
- 公共知识库文档（`user_id` 为空）任何登录用户都可读版本，但回滚返回 `400`（项目没有管理员语义）。

**与 Planner 的衔接（P1）**：Planner 规划期直接检索旅行知识库，命中结果写入 `plan.extras.citations`、
`AgentResult.citations` 与 SSE `rag_result`；research-agent 也把 `query_travel_knowledge` 列入只读工具白名单。
引用只来自真实检索命中，低相似度（< 0.05）噪声不进入方案。

---

## 12. 系统（1 个）

### 12.1 GET /api/system/status — 运行能力自检
鉴权：登录 ｜ 源码：`SystemController#status` ｜ 参数：无（不返回任何密钥）
```json
{"code":0,"message":"success","data":{"llmConfigured":true,"llmVerified":false,"llmModel":"deepseek-flash","llmHint":"已接入大模型：意图识别、方案生成与文案润色走模型","amapConfigured":false,"geoProvider":"osm","geoHint":"未配置 AMAP_KEY：城市定位退回 OSM Nominatim，国内网络可能较慢或失败","timezone":"Asia/Shanghai","routeProvider":"estimated","routeConfigured":false,"amapVerified":false,"routeLastError":null,"routeHint":"路线为本地距离估算（未配置 AMAP_KEY / 高德 MCP）：距离与耗时为近似值","weatherProvider":"open-meteo","weatherLastError":null,"weatherHint":"真实天气：Open-Meteo（免费）","priceProvider":"reference-estimate","priceHint":"价格均为本地参考估算，未接入实时房态/库存","pendingPlanStore":"database","rateLimitEnabled":true,"llmQuotaUsed":3,"llmQuotaLimit":50}}
```
错误：`401`。`llmQuotaUsed` / `llmQuotaLimit` 仅在 `travel.llm.quota-daily-limit > 0` 且已登录时出现。
**P0 起不再返回 `ocrConfigured` / `ocrHint`**：旧的本地 Tesseract 发票/OCR 能力已退役；
V32 的发票 OCR 是可选的百度云增强项（`OCR_PROVIDER` / `OCR_BAIDU_*`，默认关闭），
不进入本文自检响应——**「配了 Key」不等于「识别可用」**，本接口不对此作承诺，识别失败在对话里如实说（§5.2）。

**「配置了」与「真的能用」必须分开报**（V2.2 验收 P2/P5）：

| 字段 | 含义 |
| --- | --- |
| `llmConfigured` | 配置里有 LLM Key |
| `llmVerified` | **最近一次真实 LLM 调用是否成功**（未调用过 = `false`） |
| `routeProvider` | 现行口径，取值域严格 `amap` \| `estimated`；`amap` ⇔ `routeConfigured && amapVerified` |
| `routeConfigured` | 配了 `AMAP_KEY` 或检测到高德 MCP 工具 |
| `amapVerified` | 最近一次真实路径调用是否成功（MCP 或 REST 任一成功） |
| `routeLastError` | 最近一次失败原因，形如 `infocode=10001, info=INVALID_USER_KEY`；含 infocode、**不含 Key**；成功/未调用为 `null` |
| `weatherProvider` | 天气来源，取值域严格 `open-meteo` \| `simulated` \| `unavailable`；生产应为 `open-meteo`，出现 `simulated` 说明误开了 `WEATHER_SIMULATION` |
| `weatherLastError` | 最近一次真实天气失败原因（`timeout` / `connection_error` 等）；成功或未调用为 `null` |
| `priceProvider` | 价格能力：恒为 `reference-estimate`（只有本地参考估算，没有实时房态/库存） |

`routeHint` 四态：已验证 amap / 未配置 / 已配置但最近失败（带 infocode）/ 已配置但尚未验证。
**无效 Key、QPS 限流重试仍失败、启动后尚未成功调用过，一律 `estimated`**——估算不得伪装成实时（ADR-006）。

---

## 13. 渠道（3 个）

### 13.1 POST /api/channels/webhook — 通用 Webhook 入口
鉴权：渠道密钥（`X-Webhook-Secret` 头，恒时比较；未启用或未配置密钥一律拒绝）｜ 源码：`WebhookChannelController#handle:43`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `username` | 是 | 已存在的本地账号（不自动注册） |
| `text` | 是 | 用户消息 |
```json
// 请求头 X-Webhook-Secret: <CHANNEL_WEBHOOK_SECRET>
// 请求
{"username":"demo","text":"帮我记一笔午餐 45 元"}
// 响应
{"code":0,"message":"success","data":{"reply":"✅ 已记账：餐饮 ¥45…"}}
```
错误：`401` `渠道密钥无效或 Webhook 渠道未启用`；`400` `请求需包含 username 与 text 字段`；`404` `本地账号不存在，请先在网页端注册`。会话记忆按账号维度共享。

### 13.2 GET /api/channels/wechat — 微信公众号接入验证
鉴权：微信签名（`sha1(sort(token, timestamp, nonce)) == signature`）｜ 源码：`WeChatOfficialAccountController#verify:66`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `signature` / `timestamp` / `nonce` / `echostr` | 是 | 微信服务器握手参数 |
响应：纯文本 `echostr`（校验通过原样返回）。
错误：`403` `wechat channel disabled`（未配置 `WECHAT_OA_TOKEN`）/ `signature mismatch`；`400` `missing params`

### 13.3 POST /api/channels/wechat — 微信公众号消息推送
鉴权：微信平台（明文模式；渠道关闭时拒绝）｜ 源码：`WeChatOfficialAccountController#receive:85` ｜ 请求体：微信 XML（`text` 消息或 `event/subscribe`）
```xml
<xml><ToUserName><![CDATA[gh_xxx]]></ToUserName><FromUserName><![CDATA[oUser123]]></FromUserName><CreateTime>1762598284</CreateTime><MsgType><![CDATA[text]]></MsgType><Content><![CDATA[你好]]></Content></xml>
```
响应：未配置客服消息时返回被动回复 XML（`MsgType=text`，内容超 2000 字截断）；配置了 `appid+secret` 时立即返回 `success` 并异步走客服消息；非文本/非关注事件返回空串。
错误：`403`（渠道关闭）；XML 解析失败或字段缺失返回 `200` + 空串。

---

## 14. 接口总表

| # | 方法 | 路径 | 鉴权 | 说明 | 源码 |
| --- | --- | --- | --- | --- | --- |
| 1 | POST | `/api/auth/register` | 免登录 | 注册 | `AuthController#register:36` |
| 2 | POST | `/api/auth/login` | 免登录 | 登录 | `AuthController#login:44` |
| 3 | GET | `/api/auth/me` | 登录 | 恢复登录态 | `AuthController#me:56` |
| 4 | POST | `/api/auth/logout` | 免登录 | 登出 | `AuthController#logout:109` |
| 5 | DELETE | `/api/auth/me` | 登录 | 注销账号（密码二次确认，清 Cookie） | `AuthController#deleteMe:92` |
| 6 | GET | `/api/auth/me/export` | 登录 | 导出本人数据（JSON，照片只给清单） | `AuthController#exportMe:76` |
| 7 | POST | `/api/access-ticket` | 登录 | 签发短期只读票据 | `AccessTicketController#issue:21` |
| 8 | GET | `/api/trips` | 登录 | 行程列表 | `TripController#list` |
| 9 | GET | `/api/trips/map` | 登录 | 足迹坐标（P1 扩展） | `TripController#map` |
| 10 | POST | `/api/trips` | 登录 | 创建行程 | `TripController#create` |
| 11 | GET | `/api/trips/{id}` | 登录 | 行程详情 | `TripController#get` |
| 12 | PUT | `/api/trips/{id}` | 登录 | 更新行程 | `TripController#update` |
| 13 | DELETE | `/api/trips/{id}` | 登录 | 删除行程 | `TripController#delete` |
| 14 | PUT | `/api/trips/{id}/status` | 登录 | 确认/取消/恢复 | `TripController#updateStatus` |
| 15 | GET | `/api/trips/{id}/itinerary` | 登录 | 行程项列表 | `TripController#itinerary` |
| 16 | POST | `/api/trips/{id}/itinerary` | 登录 | 新增行程项 | `TripController#addItem` |
| 17 | GET | `/api/trips/{id}/route-legs` | 登录 | 每日路线段 | `TripController#routeLegs` |
| 18 | GET | `/api/trips/{id}/budget` | 登录 | 预算执行对账 | `TripController#budget` |
| 19 | GET | `/api/trips/{id}/review` | 登录 | 旅行回顾（P1） | `TripController#review` |
| 20 | GET | `/api/trips/{id}/events` | 登录 | 旅行事件流（P1） | `TripController#events` |
| 21 | GET | `/api/trips/{id}/guidebook.pdf` | 登录 | 路书 PDF 导出 | `TripController#guidebook` |
| 22 | PUT | `/api/itinerary/{id}` | 登录 | 修改行程项 | `ItineraryController#update` |
| 23 | DELETE | `/api/itinerary/{id}` | 登录 | 删除行程项 | `ItineraryController#delete` |
| 24 | PUT | `/api/itinerary/{id}/status` | 登录 | 行程项状态推进（P1） | `ItineraryController#updateStatus` |
| 25 | POST | `/api/agent/chat` | 登录 | 同步对话 | `AgentController#chat` |
| 26 | GET | `/api/agent/stream` | 登录/票据 | SSE 流式对话 | `AgentController#stream` |
| 27 | POST | `/api/agent/stream` | 登录 | SSE 流式对话（表单） | `AgentController#streamPost` |
| 28 | POST | `/api/agent/confirm` | 登录 | 确认提交方案 | `AgentController#confirm` |
| 29 | POST | `/api/agent/cancel` | 登录 | 取消方案 | `AgentController#cancel` |
| 30 | POST | `/api/agent/undo` | 登录 | 撤销上一次修改 | `AgentController#undo` |
| 31 | GET | `/api/agent/plans/{planId}` | 登录 | 取回待确认方案 | `AgentController#pending` |
| 32 | GET | `/api/agent/runs` | 登录 | 运行列表 | `AgentController#runs` |
| 33 | GET | `/api/agent/runs/{runId}` | 登录 | 运行明细（任务图 + 工具调用 + 花费快照） | `AgentController#runDetail` |
| 34 | GET | `/api/agent/runs/{runId}/events` | 登录/票据 | SSE 断线续传（P0；对 OpenAPI 隐藏） | `AgentController#runEvents` |
| 35 | GET | `/api/agent/executions/{executionId}` | 登录 | 同步台账 | `AgentController#executionDetail` |
| 36 | POST | `/api/agent/executions/{executionId}/retry` | 登录 | 重试失败模块 | `AgentController#executionRetry` |
| 37 | GET | `/api/agent/conversations` | 登录 | 最近会话列表（V23） | `AgentController#conversations` |
| 38 | GET | `/api/agent/conversations/{conversationId}/messages` | 登录 | 会话消息回填（V23） | `AgentController#conversationMessages` |
| 39 | POST | `/api/agent/conversation/clear` | 登录 | 清空会话 | `AgentController#clearConversation` |
| 40 | PATCH | `/api/agent/conversations/{conversationId}` | 登录 | 重命名会话（V23；空标题=恢复自动标题） | `AgentController#renameConversation` |
| 41 | DELETE | `/api/agent/conversations/{conversationId}` | 登录 | 删除会话（V23，幂等） | `AgentController#deleteConversation` |
| 42 | GET | `/api/trips/{tripId}/expenses` | 登录 | 支出列表 | `ExpenseController#list:30` |
| 43 | POST | `/api/trips/{tripId}/expenses` | 登录 | 记一笔 | `ExpenseController#add:41` |
| 44 | GET | `/api/expenses/overview` | 登录 | 记账总览 | `ExpenseController#overview:55` |
| 45 | DELETE | `/api/expenses/{id}` | 登录 | 删除支出 | `ExpenseController#delete:61` |
| 46 | POST | `/api/trips/{tripId}/photos` | 登录 | 上传照片 | `PhotoController#upload:27` |
| 47 | GET | `/api/trips/{tripId}/photos` | 登录 | 照片列表 | `PhotoController#list:37` |
| 48 | GET | `/api/photos/{id}/raw` | 登录/票据 | 读取原图 | `PhotoController#raw:54` |
| 49 | DELETE | `/api/photos/{id}` | 登录 | 删除照片 | `PhotoController#delete:76` |
| 50 | GET | `/api/reminders` | 登录 | 提醒板 | `ReminderController#board:22` |
| 51 | POST | `/api/reminders/schedule/{id}/dismiss` | 登录 | 忽略日程提醒 | `ReminderController#dismissSchedule:28` |
| 52 | POST | `/api/reminders/trip/{id}/dismiss` | 登录 | 忽略出发提醒 | `ReminderController#dismissTrip:35` |
| 53 | GET | `/api/memos` | 登录 | 备忘列表 | `MemoController#list` |
| 54 | POST | `/api/memos` | 登录 | 新增备忘 | `MemoController#add` |
| 55 | POST | `/api/memos/{id}/toggle` | 登录 | 切换完成状态 | `MemoController#toggle` |
| 56 | DELETE | `/api/memos/{id}` | 登录 | 删除备忘 | `MemoController#delete` |
| 57 | GET | `/api/memos/open-count` | 登录 | 未完成备忘数量 | `MemoController#openCount` |
| 58 | GET | `/api/schedule` | 登录 | 日程列表 | `ScheduleController#list` |
| 59 | POST | `/api/schedule` | 登录 | 新增日程 | `ScheduleController#add` |
| 60 | POST | `/api/schedule/{id}/toggle` | 登录 | 切换完成状态 | `ScheduleController#toggle` |
| 61 | DELETE | `/api/schedule/{id}` | 登录 | 删除日程 | `ScheduleController#delete` |
| 62 | POST | `/api/knowledge/add` | 登录 | 旅行知识库录入 | `TravelKnowledgeController#addKnowledge` |
| 63 | POST | `/api/knowledge/search` | 登录 | 知识库检索 | `TravelKnowledgeController#searchKnowledge` |
| 64 | GET | `/api/knowledge/documents/{id}/versions` | 登录 | 版本列表 | `TravelKnowledgeController#versions` |
| 65 | GET | `/api/knowledge/documents/{id}/versions/{versionNo}` | 登录 | 版本详情 | `TravelKnowledgeController#version` |
| 66 | POST | `/api/knowledge/documents/{id}/rollback/{versionNo}` | 登录 | 回滚版本 | `TravelKnowledgeController#rollback` |
| 67 | GET | `/api/system/status` | 登录 | 运行能力自检 | `SystemController#status` |
| 68 | POST | `/api/channels/webhook` | 渠道密钥 | 通用 Webhook | `WebhookChannelController#handle:43` |
| 69 | GET | `/api/channels/wechat` | 微信签名 | 公众号接入验证 | `WeChatOfficialAccountController#verify:66` |
| 70 | POST | `/api/channels/wechat` | 微信平台 | 公众号消息推送 | `WeChatOfficialAccountController#receive:85` |

---

## 15. 接口总数统计

### 15.1 按 Controller（与源码映射注解逐一核对）

| Controller | 接口数 | 源码文件 |
| --- | --- | --- |
| `AuthController` | 6 | `controller/AuthController.java` |
| `AccessTicketController` | 1 | `controller/AccessTicketController.java` |
| `TripController` | 14 | `controller/TripController.java` |
| `ItineraryController` | 3 | `controller/ItineraryController.java` |
| `AgentController` | 17 | `controller/AgentController.java` |
| `ExpenseController` | 4 | `controller/ExpenseController.java` |
| `PhotoController` | 4 | `controller/PhotoController.java` |
| `ReminderController` | 3 | `controller/ReminderController.java` |
| `MemoController` | 5 | `controller/MemoController.java` |
| `ScheduleController` | 4 | `controller/ScheduleController.java` |
| `TravelKnowledgeController` | 5 | `controller/TravelKnowledgeController.java` |
| `SystemController` | 1 | `controller/SystemController.java` |
| `WeChatOfficialAccountController` | 2 | `channel/WeChatOfficialAccountController.java` |
| `WebhookChannelController` | 1 | `channel/WebhookChannelController.java` |
| **合计** | **70** | 12 个 controller 包文件（67 个接口）+ 2 个 channel 包文件（3 个接口） |

### 15.2 按业务模块

| 模块 | 章 | 数量 |
| --- | --- | --- |
| 认证与票据 | §2 | 7 |
| 行程 | §3 | 14 |
| 行程项 | §4 | 3 |
| Agent 对话与方案 | §5 | 17 |
| 记账 | §6 | 4 |
| 照片 | §7 | 4 |
| 提醒 | §8 | 3 |
| 备忘 | §9 | 5 |
| 日程 | §10 | 4 |
| 旅行知识库 | §11 | 5 |
| 系统 | §12 | 1 |
| 渠道 | §13 | 3 |
| **合计** | — | **70** |

### 15.3 自检结论

- 源码映射注解统计：12 个 controller 共 **67** 个 `@*Mapping` + 2 个 channel 文件共 **3** 个 = **70**，与第 14 章逐条一致。
- **P0 删除的 22 个接口**（Office 8、Report 2、旧 Learning 8、Health 4）不再存在；原 Learning 的 RAG 三接口已迁到
  `/api/knowledge/documents/**`，知识录入/检索改为 `/api/knowledge/add`、`/api/knowledge/search`。
- **P1 新增 3 个接口**：`PUT /api/itinerary/{id}/status`、`GET /api/trips/{id}/review`、`GET /api/trips/{id}/events`；
  `GET /api/trips/map` 为既有端点扩展字段，不计入新增数。
- **V23 新增 2 个会话读取端点**：`GET /api/agent/conversations`、`GET /api/agent/conversations/{conversationId}/messages`。
- **P0 新增 1 个 SSE 续传端点**：`GET /api/agent/runs/{runId}/events`（`@Hidden`，不进入 OpenAPI 快照）。
- README §5 已同步为同一份清单，不再保留退役模块。

---

*文档核验方式：逐条对照 `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` 与对应 Controller 方法、DTO、`GlobalExceptionHandler`、`SecurityConfig`；所有示例字段均来自真实 DTO/实体字段。*