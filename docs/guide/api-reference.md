# REST API 参考

本文是 AI 个人助理后端（Spring Boot 3）的**逐接口契约手册**，面向前端开发者与第三方集成者。
所有接口、字段、校验规则和错误码均取自 `travel-agent-server` 源码，未使用推测内容。

- 源码根：`travel-agent-server/src/main/java/com/travel/agent/`
- 接口总数：**72 个**（15 个业务 Controller 的 69 个 + 渠道包 3 个），清单见第 16、17 章
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

免登录白名单（`SecurityConfig#securityFilterChain:62-65`）：

| 路径 | 说明 |
| --- | --- |
| `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/logout` | 无需登录 |
| `/api/channels/**` | 渠道入口，以密钥 / 微信签名自证身份（见第 15 章） |
| `/h2-console/**`、`/error`、`/actuator/health`、`OPTIONS *` | 开发、探活、预检 |
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

**仅凭票据认证（没有 Cookie / `Authorization`）的请求 = 只读会话**：记账 / 备忘 / 日程 / 日报四类直接写入意图被拒绝，
`message` 为 `当前连接为只读票据会话，记账/备忘/日程/日报请在网页端操作。`（`status` 仍是 `COMPLETED`，不按错误处理）；
规划与改行程仍可用（只产出待确认方案，提交需 `POST /api/agent/confirm`，而 POST 不接受票据）。带 Cookie 的浏览器请求行为不变。

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
| `GET /api/agent/stream`、`POST /api/agent/stream` | `text/event-stream`（SSE，见 §5.3/§5.4） |
| `GET /api/trips/{id}/guidebook.pdf` | `application/pdf` 字节流 |
| `GET /api/photos/{id}/raw` | 图片字节流（`Content-Type` 由文件头推导） |
| `GET/POST /api/channels/wechat` | 纯文本 / XML（微信协议） |

### 1.5 错误码 / HTTP 状态码映射

`GlobalExceptionHandler` 约定 **body 里的 `code` 与 HTTP 状态码一致**：

| code / HTTP | 触发条件 | 典型 message |
| --- | --- | --- |
| `400` | `@Valid` 校验失败、请求体不可解析、缺参数、`BusinessException(400)` | `<字段> <校验提示>`；`结束日期不能早于开始日期` |
| `401` | 未登录 / 登录过期（安全链）、登录失败、用户不存在 | `未登录或登录状态已过期`；`用户名或密码错误` |
| `403` | `AccessDeniedException` | `没有权限执行该操作` |
| `404` | 资源不存在或无权访问 | `行程不存在或无权访问`；`照片不存在` |
| `409` | 唯一约束/数据冲突、发票重复记账 | `数据冲突，请检查是否重复提交或文本过长`；`该发票已经记过账了` |
| `422` | 发票文件内容不可提取（`BusinessException(422)`） | `这份 PDF 没有可提取的文字…`；`图片发票需要 OCR 引擎…` |
| `429` | 触发限流（响应带 `Retry-After`） | `操作过于频繁，请 N 秒后再试` |
| `500` | 未捕获异常（不返回内部细节） | `服务端异常，请稍后重试` |

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

**已接入数据库分页（`Pageable` + `Page`）的 5 个端点**（`util/Paging` 的 `pageable` / `normalizeLimit` / `normalizeOffset`）：

| 端点 | 说明 |
| --- | --- |
| `GET /api/trips` | 行程列表（§3.1） |
| `GET /api/trips/{id}/expenses` | 行程支出列表（§6.1） |
| `GET /api/memos` | 备忘列表（§9.1） |
| `GET /api/health/records` | 健康记录（§13.3） |
| `GET /api/reminders` | 提醒板（§8.1）；`data` 仍是 `{items,count,serverTime}`，`items` 分页、`count` 为总数 |

> **行为变化（重要）**：这 5 个端点默认条数从 `500` 降为 **20**。不传 `limit` / `offset` 的老客户端只会拿到第一页 20 条，
> 必须读 `pagination.hasMore` 翻页（或显式传更大的 `limit`，上限 `200`）。

**仍走服务端兜底截断、无分页元数据**的端点：`/api/photos**`、`/api/schedule`、`/api/office/{mail,minutes,invoice}`、
`/api/office/report`、`/api/learning/artifacts` 等（`util/Paging.slice`，默认 `500`、上限 `1000`，响应不带 `pagination`）。

**聚合下推**（响应 JSON 结构不变，只是改成数据库聚合）：`GET /api/expenses/overview` 的分类 / 支付方式 / 行程维度改为
`GROUP BY` 聚合 + 最近 8 条单独查询；`ExpenseService.totalOf` 改 `SUM`；备忘待办数改 `COUNT`；健康 7 天窗口与均值改数据库聚合。

### 1.7 上传与限流

- 上传上限：Spring multipart `max-file-size=10MB` / `max-request-size=12MB`；照片单张 ≤10MB、单行程 ≤200 张、单用户 ≤500MB；发票文件 ≤10MB。
- 限流（`RateLimitFilter`，默认开启、窗口 300 秒）：`/api/auth/**` 按 IP 10 次；`/api/agent/**` 按用户 30 次；照片/发票上传 60 次；其余 `/api/**` 按用户 600 次。
  - `GET /api/auth/me` 走**独立会话桶** `RATE_LIMIT_SESSION`（默认 60/窗口），避免刷新页面被登录桶（`RATE_LIMIT_AUTH`，默认 10）踢回登录页；前端只把 `401` 当登出。
  - `Retry-After` 最小为 `1`（不再出现 `Retry-After: 0`）；Redis fail-open 时打 WARN 并计 `ratelimit.failopen` 指标。
- 所有失败响应中 `data` 均为 `null`；成功但无返回值的接口 `data` 也为 `null`。

---

## 2. 认证与票据（5 个）

### 2.1 POST /api/auth/register — 注册
鉴权：免登录 ｜ 源码：`AuthController#register:27` ｜ 成功时响应头带 `Set-Cookie: travel_agent_token=...`
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
鉴权：免登录 ｜ 源码：`AuthController#login:35` ｜ 成功时 `Set-Cookie`
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
鉴权：登录 ｜ 源码：`AuthController#me:47` ｜ 无请求体（凭 Cookie/头恢复用户资料，`token` 为 `null`）
```json
{"code":0,"message":"success","data":{"token":null,"id":2,"username":"traveler","nickname":"小旅"}}
```
错误：`401` 未登录或登录已过期

### 2.4 POST /api/auth/logout — 登出
鉴权：免登录（幂等）｜ 源码：`AuthController#logout:58` ｜ 无参数；清空登录 Cookie
```json
{"code":0,"message":"success","data":null}
```
错误：`500`（极少数 Cookie 写失败场景）

### 2.5 POST /api/access-ticket — 签发短期只读票据
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

## 3. 行程（11 个）

### 3.1 GET /api/trips — 行程列表
鉴权：登录 ｜ 源码：`TripController#list:40` ｜ 错误：`401`
参数：`limit`（默认 20，上限 200）、`offset`（默认 0）；响应带 `pagination`（见 §1.6）
```json
{"code":0,"message":"success","data":[{"id":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-01"}],"pagination":{"total":1,"limit":20,"offset":0,"hasMore":false}}
```
`phase` 由日期派生：`UPCOMING` / `ONGOING` / `PAST` / `CANCELLED`；`daysUntilStart` 仅 `UPCOMING` 有值。

### 3.2 GET /api/trips/map — 首页足迹地图坐标
鉴权：登录 ｜ 源码：`TripController#map:50` ｜ 错误：`401`
参数：无；已取消行程不计入；坐标可能为 `null`（图集与联网均未命中）
```json
{"code":0,"message":"success","data":[{"tripId":1,"name":"杭州三日","destination":"杭州","status":"PLANNING","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"lat":30.25,"lng":120.16}]}
```

### 3.3 POST /api/trips — 创建行程
鉴权：登录 ｜ 源码：`TripController#create:56`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 否 | ≤200 字符，缺省 `我的旅行` |
| `destination` | 否 | ≤200 字符 |
| `startDate` | 否 | `yyyy-MM-dd`，缺省明天 |
| `endDate` | 否 | `yyyy-MM-dd`，缺省 `startDate + 2 天`（共 3 天） |
| `budget` | 否 | `BigDecimal` ≥ 0，缺省 `5000` |
```json
// 请求
{"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000}
// 响应
{"code":0,"message":"success","data":{"id":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-08"}}
```
错误：`400` 校验失败 / `结束日期不能早于开始日期` / `开始日期不是有效日期，请使用 yyyy-MM-dd`；`401`

### 3.4 GET /api/trips/{id} — 行程详情
鉴权：登录 ｜ 源码：`TripController#get:62` ｜ 参数：路径 `id` ｜ 响应结构同 §3.3
```json
{"code":0,"message":"success","data":{"id":1,"name":"杭州三日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-22","budget":5000.00,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-01"}}
```
错误：`401`；`404` `行程不存在或无权访问`

### 3.5 PUT /api/trips/{id} — 更新行程
鉴权：登录 ｜ 源码：`TripController#update:68` ｜ 请求体同 §3.3（仅传需要修改的字段）
```json
// 请求
{"name":"杭州四日","endDate":"2026-11-23","budget":6000}
// 响应
{"code":0,"message":"success","data":{"id":1,"name":"杭州四日","destination":"杭州","startDate":"2026-11-20","endDate":"2026-11-23","budget":6000.00,"status":"PLANNING","phase":"UPCOMING","daysUntilStart":12,"createdAt":"2026-11-01"}}
```
错误：`400` 结束日期早于开始日期 / `缩短后第 N 天的安排会超出范围…`；`401`；`404`
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
{"code":0,"message":"success","data":[{"id":11,"tripId":1,"dayIndex":1,"itemType":"attraction","title":"西湖断桥","startTime":"2026-11-20T09:00:00","endTime":"2026-11-20T11:00:00","latitude":30.259,"longitude":120.148,"cost":0.00,"tags":["室外","免费"],"description":"清晨人少"}]}
```
错误：`401`；`404`

### 3.9 GET /api/trips/{id}/route-legs — 每日路线段（V2.2 FR-07）
鉴权：登录 ｜ 源码：`TripController#routeLegs` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":[
  {"dayIndex":1,"sequence":1,"fromItemId":11,"toItemId":12,"fromTitle":"外滩","toTitle":"豫园",
   "fromLatitude":31.2397,"fromLongitude":121.4998,"toLatitude":31.227,"toLongitude":121.4921,
   "distanceKm":1.9,"walkMinutes":25,"driveMinutes":9,"taxiCost":17.0,"source":"estimated"}]}
```
按天输出「相邻且有坐标」的行程点之间的路段：`sequence` 为当天内段序号（从 1 开始）；
`source` 标注距离来源——`amap`（高德真实路径）或 `estimated`（本地 haversine 估算）。
**`taxiCost` 始终为本地估算**（未接入计价 Provider），前端不得显示成实时价格。
无坐标条目（如「当日交通（打车）」费用汇总行）自动跳过，不产生假路线。
错误：`401`；`404`（他人行程同样 404）

### 3.10 POST /api/trips/{id}/itinerary — 新增行程项
鉴权：登录 ｜ 源码：`TripController#addItem:106`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `title` | 是 | ≤200 字符 |
| `dayIndex` | 否 | 第几天，从 1 开始，缺省 1 |
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
错误：`400` 校验失败；`401`；`404`（手动新增会写入撤销历史）

### 3.11 GET /api/trips/{id}/budget — 预算执行对账
鉴权：登录 ｜ 源码：`TripController#budget:100` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":{"tripId":1,"tripName":"杭州三日","budget":5000.00,"planned":4820.00,"actual":365.00,"remaining":4635.00,"variance":-4455.00,"executionRate":0.073,"overBudget":false,"planOverBudget":false,"categories":[{"category":"住宿","planned":1800.00,"actual":0.00}],"daily":[{"day":1,"planned":1600.00,"actual":120.00,"variance":-1480.00}]}}
```
错误：`401`；`404`。`variance = actual - planned`（正数=比计划多花）；`daily[].day=0` 表示行程日期之外的支出。

### 3.12 GET /api/trips/{id}/guidebook.pdf — 路书导出
鉴权：登录 ｜ 源码：`TripController#guidebook:118` ｜ 参数：路径 `id`
响应：`application/pdf` 字节流，`Content-Disposition: inline; filename="trip-1-guidebook.pdf"`（浏览器内联预览，可另存）。
错误：`401`；`404`

---

## 4. 行程项（2 个）

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
鉴权：登录 ｜ 源码：`ItineraryController#delete:40` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404`；删除会进入撤销链

---

## 5. Agent 对话、方案与追踪（12 个）

### 5.1 Agent 统一返回结构 `AgentResult`

`chat` / `confirm` / `cancel` / `undo` / `plans/{planId}` 的 `data` 均为 `AgentDtos.AgentResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `planId` | `String` | 待确认方案 ID（`NEED_CONFIRMATION` 时非空） |
| `tripId` | `Long` | 关联行程 ID |
| `message` | `String` | 可直接展示的回复文本 |
| `intent` | `String` | `PLAN_TRIP` / `MODIFY_ITINERARY` / `QUERY_BUDGET` / `RECORD_EXPENSE` / `ADD_MEMO` / `CHITCHAT` / `REJECTED` / `ERROR` 等 |
| `status` | `String` | `COMPLETED` / `NEED_CONFIRMATION` / `FAILED` |
| `focus` | `Object` | `{type:itinerary\|map\|budget, tripId, day, itemId, latitude, longitude}` |
| `diff` | `Object` | `{changes:[{type:ADD\|REMOVE\|MODIFY,itemId,itemType,title,detail,cost,dayIndex,startTime}], budgetBefore, budgetAfter}` |
| `budget` | `Object` | `{total, previousTotal, difference, breakdown:{分类:金额}}` |
| `confirmation` | `Object` | `{planId, summary, riskLevel:LOW\|MEDIUM\|HIGH, contentHash}`；`contentHash` 是方案内容的 SHA-256（V2.2 FR-08），确认时回传即可绑定「用户看到的内容」 |
| `warnings` | `String[]` | 反思/风险提示 |
| `execution` | `Object` | 仅 `confirm` 成功路径：`{executionId, planId, tripId, status:SUCCEEDED\|PARTIAL_FAILED\|FAILED, modules:[...]}`（V2.2 FR-09，见 §5.11） |

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
{"message":"帮我规划杭州三日游，预算 5000","conversationId":"c-1a2b3c"}
// 响应（节选）
{"code":0,"message":"success","data":{"planId":"plan-8f3c","tripId":null,"message":"已为你生成杭州三日行程（3 天 8 个安排），请确认后提交。","intent":"PLAN_TRIP","status":"NEED_CONFIRMATION","focus":{"type":"itinerary","tripId":null,"day":1,"itemId":null,"latitude":null,"longitude":null},"diff":{"changes":[{"type":"ADD","itemId":null,"itemType":"attraction","title":"西湖断桥","detail":null,"cost":0.00,"dayIndex":1,"startTime":"09:00"}],"budgetBefore":null,"budgetAfter":4820.00},"budget":{"total":4820.00,"previousTotal":null,"difference":null,"breakdown":{"住宿":1800.00}},"confirmation":{"planId":"plan-8f3c","summary":"3 天 8 个安排","riskLevel":"MEDIUM"},"warnings":[]}}
```
错误：`400` 校验失败；`401`；`429` 限流

### 5.3 GET /api/agent/stream — SSE 流式对话（EventSource）
鉴权：登录（**EventSource 用 `?ticket=` 票据**，不能用 `Authorization` 头）｜ 源码：`AgentController#stream:51`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `message` | 是 | 用户消息 |
| `ticket` | 否* | 短期只读票据，**用途必须是 `agent-stream`**（见 §1.3）；浏览器 EventSource 必须提供 |
| `tripId` / `currentDay` | 否 | 同 §5.2 |
| `conversationId` | 否 | 非法值（超长/非法字符）按单轮处理 |
| `selType` / `selId` / `selTitle` | 否 | 选中元素三元组（`selType` 存在才算） |
| `lat` / `lng` | 否 | 地图中心，需同时提供 |
响应 `text/event-stream`，超时 180 秒；事件名与 `data`：

```
event:start
data:{"sessionId":"3f1c2e9a-7c41-4c1e-9f5a-2b8e6d0a1c33"}

event:run
data:{"runId":"8f3c…"}

event:intent
data:{"intent":"PLAN_TRIP","target":"NONE","constraints":{},"toolName":null,"toolArgs":null}

event:planning
data:{"message":"正在为你规划行程…"}

event:complete
data:{"planId":"plan-8f3c","tripId":null,"message":"…","intent":"PLAN_TRIP","status":"NEED_CONFIRMATION"}
```
其余事件：`run`（V2.2 FR-10，紧随 `start`，`{runId}` 可查 §5.11 的完整追踪）、`tool_call`（`{tool,args}`）、
`tool_result`（`{tool,result}`）、`plan_update`（`{diff,budget}`）、`focus`、
`confirmation`（含 `contentHash`，确认时回传见 §5.5）、`rag_result`（`{sources}`）、`error`（`{message}`）。
错误：`401`（票据无效/过期，含用途不匹配）；线程池满时推送 `event:error` 后关闭。

> 响应头带 `Referrer-Policy: no-referrer`，避免 `?ticket=` 随 Referer 外泄。
> 仅凭票据认证（不带 Cookie）的请求是**只读会话**：记账/备忘/日程/日报四类写意图会被拒绝，`message` 见 §1.3，规划仍可用。

### 5.4 POST /api/agent/stream — SSE 流式对话（结构化表单入口）
鉴权：登录 ｜ 源码：`AgentController#streamPost:75`
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
错误：`400` 校验失败；`401`；`429`

### 5.5 POST /api/agent/confirm — 确认提交方案
鉴权：登录 ｜ 源码：`AgentController#confirm:117`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `planId` | 是 | 方案 ID（来自 `confirmation.planId`） |
| `contentHash` | 否 | 方案内容指纹（`confirmation.contentHash`，≤64）；**V2.2 FR-08**：携带时校验「确认的就是看到的内容」，不传走既有版本校验（向后兼容） |
```json
// 请求
{"planId":"plan-8f3c","contentHash":"a3f1…9c2e"}
// 响应（方案已过期时为 200 + status=FAILED）
{"code":0,"message":"success","data":{"planId":"plan-8f3c","tripId":1,"message":"已提交，行程已更新。\n✅ 已同步：日程时间块、预算快照、足迹地图。","intent":null,"status":"COMPLETED","focus":null,"diff":null,"budget":null,"confirmation":null,"warnings":null,"execution":{"executionId":"e-77b1","planId":"plan-8f3c","tripId":1,"status":"SUCCEEDED","modules":[{"module":"calendar","status":"SUCCEEDED","detail":"3 天行程已同步为日程时间块（本次新建 3 条）","idempotencyKey":"plan:plan-8f3c:module:calendar","retryCount":null,"error":null}]}}}
```
错误：`400` `planId 不能为空`；`401`。确认前行程被改动（`planRevision` 不一致）或方案内容被改（`contentHash` 不一致）时返回 200、`status=FAILED` 并提示方案失效；方案不存在/过期同理，不抛 404。

**确认后的跨模块同步（V2.2 FR-09）**：提交成功后按 `calendar` / `budget` / `footprint` 逐模块同步并写台账，
模块级失败**不回滚**已保存的行程，响应 `execution.status` 为 `PARTIAL_FAILED` / `FAILED`，
失败模块可用 §5.12 单独重试（成功项不重复创建）。口径红线：`budget` 只记计划快照（估算不会自动变成已消费），
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
| `tripId` | 是 | 行程 ID |
```json
// 请求
{"tripId":1}
// 响应
{"code":0,"message":"success","data":{"planId":null,"tripId":1,"message":"已撤销上一步操作（还原 8 项安排）","intent":null,"status":"COMPLETED","focus":null,"diff":null,"budget":null,"confirmation":null,"warnings":null}}
```
错误：`400` `没有可撤销的操作` / `撤销失败: …`；`401`；`404`。撤销「新建行程」会删除整个行程。

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

### 5.10 GET /api/agent/runs — 运行列表（V2.2 FR-10）
鉴权：登录 ｜ 源码：`AgentController#runs` ｜ 参数：`limit`（默认 20，上限 50）
```json
{"code":0,"message":"success","data":[{"runId":"8f3c…","tripId":null,"message":"帮我规划杭州三日游","intent":"PLAN_TRIP","status":"SUCCEEDED","error":null,"startedAt":"2026-11-18 12:03:11","endedAt":"2026-11-18 12:03:19","durationMs":8123}]}
```
`status`：`RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED`（`NEED_CONFIRMATION` 记为 `SUCCEEDED`——规划已完成，等待用户动作）。
错误：`401`

### 5.11 GET /api/agent/runs/{runId} — 运行明细（任务链 + 工具调用）
鉴权：登录 ｜ 源码：`AgentController#runDetail` ｜ 参数：路径 `runId`
```json
{"code":0,"message":"success","data":{
  "run":{"runId":"8f3c…","status":"SUCCEEDED","intent":"PLAN_TRIP","durationMs":8123,"error":null},
  "tasks":[{"taskId":"t1","receiver":"intent-agent","taskType":"INTENT","status":"SUCCEEDED","summary":"识别意图: PLAN_TRIP","error":null,"durationMs":210},
           {"taskId":"t2","receiver":"planner-agent","taskType":"PLAN","status":"SUCCEEDED","summary":"生成 9 项操作","error":null,"durationMs":6200}],
  "toolCalls":[{"id":1,"taskId":"t2","toolName":"search_hotel","success":true,"errorCode":null,"latencyMs":12,"argsDigest":"{\"city\":\"杭州\"}","resultDigest":"[…]","startedAt":"2026-11-18 12:03:14"}]}}
```
`receiver`：`intent-agent` / `research-agent` / `planner-agent` / `executor-agent` / `reflect-agent` / `tool-invoker`；
`taskType`：`INTENT` / `RESEARCH` / `PLAN` / `PREVIEW` / `REVIEW` / `TOOL` / `COMMIT`。
摘要字段为截断 JSON（不含密钥）。错误：`401`；`404` `运行记录不存在`（他人的 run 同样 404）

### 5.12 GET /api/agent/executions/{executionId} — 同步台账（V2.2 FR-09）
鉴权：登录 ｜ 源码：`AgentController#executionDetail` ｜ 参数：路径 `executionId`
```json
{"code":0,"message":"success","data":{"executionId":"e-77b1","planId":"plan-8f3c","tripId":1,"status":"PARTIAL_FAILED","modules":[
  {"module":"calendar","status":"SUCCEEDED","detail":"3 天行程已同步为日程时间块（本次新建 3 条）","idempotencyKey":"plan:plan-8f3c:module:calendar","retryCount":null,"error":null},
  {"module":"budget","status":"FAILED","detail":null,"idempotencyKey":"plan:plan-8f3c:module:budget","retryCount":0,"error":"行程不存在"}]}}
```
`module`：`calendar`（日程时间块）/ `budget`（计划预算快照）/ `footprint`（足迹计划标记）。
错误：`401`；`404` `执行记录不存在`

### 5.13 POST /api/agent/executions/{executionId}/retry — 重试失败模块
鉴权：登录 ｜ 源码：`AgentController#executionRetry` ｜ 参数：路径 `executionId`（无请求体）
```json
{"code":0,"message":"success","data":{"executionId":"e-77b1","status":"SUCCEEDED","modules":[…]}}
```
只重试 `FAILED` 模块，`SUCCEEDED` 模块原样保留（幂等键保证不重复创建）；`retryCount` 递增。
错误：`400` `该执行记录没有可重试的模块`；`401`；`404`

### 5.14 目的地城市名匹配（`util/CityNames`）

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

### 6.3 GET /api/expenses/overview — 跨行程记账总览
鉴权：登录 ｜ 源码：`ExpenseController#overview:55` ｜ 参数：无
```json
{"code":0,"message":"success","data":{"totalSpent":3560.00,"monthSpent":1240.00,"count":18,"byPaymentMethod":{"微信支付":2200.00,"现金":1360.00},"byCategory":{"餐饮":800.00,"住宿":2400.00,"其他":360.00},"byTrip":[{"tripId":1,"tripName":"杭州三日","total":2400.00}],"recent":[{"id":31,"tripId":1,"tripName":"杭州三日","category":"餐饮","amount":45.00,"note":"午餐","paymentMethod":"微信支付","spentAt":"2026-11-08","createdAt":"2026-11-08 12:30"}]}}
```
错误：`401`。`recent` 最多 8 条；已删除行程的支出归入 `tripId=0` / `已删除的行程`。第九批起分类 / 支付方式 / 行程维度改为数据库 `GROUP BY` 聚合、`recent` 单独查询，**响应 JSON 结构不变**。

### 6.4 DELETE /api/expenses/{id} — 删除支出
鉴权：登录 ｜ 源码：`ExpenseController#delete:61` ｜ 参数：路径 `id`
```json
{"code":0,"message":"success","data":null}
```
错误：`401`；`404` `支出记录不存在`

---

## 7. 照片（4 个）

### 7.1 POST /api/trips/{tripId}/photos — 上传照片
鉴权：登录 ｜ 源码：`PhotoController#upload:28` ｜ Content-Type：`multipart/form-data`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | 图片文件，单张 ≤10MB；扩展名或 MIME 需在 jpg/jpeg/png/webp/gif/bmp/heic/heif 白名单内，最终以文件头识别为准（svg 拒绝） |
```json
// 响应
{"code":0,"message":"success","data":{"id":21,"tripId":1,"originalName":"xihu.jpg","contentType":"image/jpeg","sizeBytes":204800,"createdAt":"2026-11-08T14:20:00","url":"/api/photos/21/raw"}}
```
错误：`400` `请选择要上传的照片` / `单张照片不能超过 10MB` / 张数或容量超限 / 格式不支持 / `文件内容不是可识别的图片，请重新选择`；`401`；`404`；`429`

### 7.2 GET /api/trips/{tripId}/photos — 照片列表
鉴权：登录 ｜ 源码：`PhotoController#list:36` ｜ 参数：路径 `tripId`、`limit`、`offset`
```json
{"code":0,"message":"success","data":[{"id":21,"tripId":1,"originalName":"xihu.jpg","contentType":"image/jpeg","sizeBytes":204800,"createdAt":"2026-11-08T14:20:00","url":"/api/photos/21/raw"}]}
```
错误：`401`；`404`

### 7.3 GET /api/photos/{id}/raw — 读取原图
鉴权：登录（`<img>` 用 `?ticket=` 票据，**用途必须是 `media-read`**，见 §1.3）｜ 源码：`PhotoController#raw:52` ｜ 参数：路径 `id`
响应：图片字节流，`Content-Type` 由文件头推导；带 `X-Content-Type-Options: nosniff`、`Content-Security-Policy: default-src 'none'; sandbox`、`Cache-Control: private, no-store`、`Referrer-Policy: no-referrer`。
错误：`401`；`404` `照片不存在` / `照片文件不存在（可能已被清理）`

### 7.4 DELETE /api/photos/{id} — 删除照片
鉴权：登录 ｜ 源码：`PhotoController#delete:70` ｜ 参数：路径 `id`
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

## 11. 办公（10 个）

### 11.1 POST /api/office/mail/classify — 邮件分类
鉴权：登录 ｜ 源码：`OfficeController#classifyMail:58`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `text` | 是 | 邮件正文，≤20000 字符 |
| `sender` / `subject` | 否 | 发件人 / 主题（≤200 字符） |
```json
// 请求
{"sender":"boss@example.com","subject":"周一评审","text":"请周一上午十点参加产品评审会，提前看下需求文档。"}
// 响应
{"code":0,"message":"success","data":{"id":1,"userId":2,"sender":"boss@example.com","subject":"周一评审","body":"请周一上午十点参加产品评审会，提前看下需求文档。","category":"会议","summary":"周一上午十点产品评审会，需提前看需求文档。","suggestedReply":"收到，我会提前准备。","createdAt":"2026-11-08T11:00:00"}}
```
错误：`400` 校验失败；`401`。分类取值：`重要`/`会议`/`账单`/`通知`/`垃圾`/`其他`。

### 11.2 GET /api/office/mail — 邮件列表
鉴权：登录 ｜ 源码：`OfficeController#mailList:76` ｜ 参数：`limit`、`offset`
```json
{"code":0,"message":"success","data":[{"id":1,"userId":2,"sender":"boss@example.com","subject":"周一评审","body":"…","category":"会议","summary":"…","suggestedReply":"…","createdAt":"2026-11-08T11:00:00"}]}
```
错误：`401`

### 11.3 POST /api/office/minutes/generate — 生成会议纪要
鉴权：登录 ｜ 源码：`OfficeController#generateMinutes:91`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `rawText` | 是 | 会议原始记录，≤50000 字符 |
| `title` | 否 | ≤200 字符，缺省 `未命名会议` |
```json
// 请求
{"title":"产品评审会","rawText":"张三：本次评审三个需求…李四：排期两周…"}
// 响应
{"code":0,"message":"success","data":{"id":2,"userId":2,"title":"产品评审会","rawText":"张三：本次评审三个需求…","content":"## 结论\n1. …","createdAt":"2026-11-08T11:20:00"}}
```
错误：`400` 校验失败；`401`

### 11.4 GET /api/office/minutes — 会议纪要列表
鉴权：登录 ｜ 源码：`OfficeController#minutesList:104` ｜ 参数：`limit`、`offset`
```json
{"code":0,"message":"success","data":[{"id":2,"userId":2,"title":"产品评审会","rawText":"…","content":"## 结论\n1. …","createdAt":"2026-11-08T11:20:00"}]}
```
错误：`401`

### 11.5 POST /api/office/invoice/extract — 粘贴文本提取发票
鉴权：登录 ｜ 源码：`OfficeController#extractInvoice:118`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `text` | 是 | 发票文本，≤20000 字符，需包含金额 |
```json
// 请求
{"text":"上海某酒店 住宿费 680.00 元 开票日期 2026-11-06"}
// 响应
{"code":0,"message":"success","data":{"id":4,"userId":2,"title":"上海某酒店","vendor":"某酒店","category":"住宿","amount":680.00,"invoiceDate":"2026-11-06","rawText":"…","sourceFile":null,"sourceType":null,"expenseId":null,"tripId":null,"createdAt":"2026-11-08T11:30:00"}}
```
错误：`400` `未能从文本中提取出发票信息，请检查内容（需包含金额）`；`401`

### 11.6 GET /api/office/invoice — 发票列表
鉴权：登录 ｜ 源码：`OfficeController#invoiceList:137` ｜ 参数：`limit`、`offset`（按开票日期倒序）
```json
{"code":0,"message":"success","data":[{"id":4,"userId":2,"title":"上海某酒店","vendor":"某酒店","category":"住宿","amount":680.00,"invoiceDate":"2026-11-06","rawText":"…","sourceFile":null,"sourceType":null,"expenseId":null,"tripId":null,"createdAt":"2026-11-08T11:30:00"}]}
```
错误：`401`。发票分类取值：`交通`/`餐饮`/`办公`/`住宿`/`其他`；`sourceType` 为 `PDF`/`IMAGE`/`TEXT`。

### 11.7 POST /api/office/invoice/upload — 上传发票文件
鉴权：登录 ｜ 源码：`OfficeController#uploadInvoice:151` ｜ Content-Type：`multipart/form-data`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | PDF 或图片（jpg/png/webp 等），≤10MB；图片需配置 Tesseract OCR |
```json
// 响应
{"code":0,"message":"success","data":{"id":5,"userId":2,"title":"上海某酒店","vendor":"某酒店","category":"住宿","amount":680.00,"invoiceDate":"2026-11-06","rawText":"…","sourceFile":"3fa2c1.pdf","sourceType":"PDF","expenseId":null,"tripId":null,"createdAt":"2026-11-08T11:40:00"}}
```
错误：`400` `请选择要上传的发票文件` / `文件不能超过 10MB` / `只支持 PDF 发票或图片（jpg/png/webp）` / `PDF 解析失败，请确认文件未损坏` / `未能从文件中提取出发票金额…`；`401`；`422` `这份 PDF 没有可提取的文字…` / `图片发票需要 OCR 引擎…` / `OCR 未能识别出文字…`；`429`

### 11.8 POST /api/office/invoice/{id}/expense — 发票一键记账
鉴权：登录 ｜ 源码：`OfficeController#invoiceToExpense:192`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `tripId` | 是 | 目标行程 ID |
| `paymentMethod` | 否 | ≤30 字符，缺省/非法值按 `现金` 处理 |
```json
// 请求
{"tripId":1,"paymentMethod":"支付宝"}
// 响应
{"code":0,"message":"success","data":{"id":32,"tripId":1,"tripName":null,"category":"住宿","amount":680.00,"note":"发票：上海某酒店 · 某酒店","paymentMethod":"支付宝","spentAt":"2026-11-06","createdAt":"2026-11-08 11:45"}}
```
错误：`400` `发票金额无效，无法记账`、校验失败；`401`；`404` `发票不存在` / `行程不存在或无权访问`；`409` `该发票已经记过账了`。记账后发票的 `expenseId`、`tripId` 会回填。

### 11.9 POST /api/office/report/generate — 生成今日日报
鉴权：登录 ｜ 源码：`ReportController#generate:26` ｜ 参数：无；同日重复调用会覆盖当日内容
```json
{"code":0,"message":"success","data":{"id":6,"userId":2,"date":"2026-11-08","content":"今日记账 2 笔，合计 ¥125；完成备忘 1 项…","createdAt":"2026-11-08T21:00:00"}}
```
错误：`401`

### 11.10 GET /api/office/report — 日报列表
鉴权：登录 ｜ 源码：`ReportController#reportList:32` ｜ 参数：`limit`、`offset`
```json
{"code":0,"message":"success","data":[{"id":6,"userId":2,"date":"2026-11-08","content":"今日记账 2 笔…","createdAt":"2026-11-08T21:00:00"}]}
```
错误：`401`

---

## 12. 学习与知识库（8 个）

### 12.1 POST /api/learning/generate — 生成学习产物
鉴权：登录 ｜ 源码：`LearningController#generate:52`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `kind` | 否 | `FRAMEWORK`（知识框架）/ `PATH`（学习路线）/ `SUMMARY`（重点归纳，缺省） |
| `topic` | 否 | ≤200 字符；缺省取 `text` 前 30 字 |
| `text` | 否 | ≤20000 字符，`SUMMARY` 的归纳素材 |
```json
// 请求
{"kind":"SUMMARY","topic":"Spring 事务传播","text":"REQUIRED 与 REQUIRES_NEW 的区别…"}
// 响应
{"code":0,"message":"success","data":{"id":9,"userId":2,"kind":"SUMMARY","topic":"Spring 事务传播","content":"1. REQUIRED 复用当前事务…","createdAt":"2026-11-08T15:00:00"}}
```
错误：`400` 校验失败；`401`

### 12.2 GET /api/learning/artifacts — 学习产物列表
鉴权：登录 ｜ 源码：`LearningController#artifacts:70` ｜ 参数：`kind`（可选过滤）、`limit`、`offset`
```json
{"code":0,"message":"success","data":[{"id":9,"userId":2,"kind":"SUMMARY","topic":"Spring 事务传播","content":"…","createdAt":"2026-11-08T15:00:00"}]}
```
错误：`401`

### 12.3 DELETE /api/learning/artifacts/{id} — 删除学习产物
鉴权：登录 ｜ 源码：`LearningController#delete:82` ｜ 参数：路径 `id`；不存在或非本人时静默成功
```json
{"code":0,"message":"success","data":null}
```
错误：`401`

### 12.4 POST /api/learning/knowledge/add — 搭建个人知识库
鉴权：登录 ｜ 源码：`LearningController#addKnowledge:99`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `content` | 是 | ≤20000 字符；按空行切块后向量化入库 |
| `title` | 否 | ≤200 字符，缺省 `未命名知识` |
```json
// 请求
{"title":"签证材料","content":"申根签证需要：护照、照片、行程单…\n\n保险需覆盖全程…"}
// 响应（第九批起多一个 versionNo：本次写入的版本号，首次录入为 1）
{"code":0,"message":"success","data":{"documentId":7,"chunks":2,"versionNo":1}}
```
错误：`400` 校验失败；`401`

### 12.5 POST /api/learning/knowledge/search — 知识库检索
鉴权：登录 ｜ 源码：`LearningController#searchKnowledge:133`（返回分数最高的 5 条；检索范围 = 公共知识 + 本人个人知识，不含他人私有文档）
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `query` | 是 | ≤500 字符 |
```json
// 请求
{"query":"申根签证需要什么材料"}
// 响应
{"code":0,"message":"success","data":[{"title":"签证材料","snippet":"申根签证需要：护照、照片、行程单…","score":0.83}]}
```
错误：`400` 校验失败；`401`

### 12.6 GET /api/knowledge/documents/{id}/versions — 版本列表
鉴权：登录 ｜ 源码：`LearningController.KnowledgeVersionController#versions:168` ｜ 参数：路径 `id`（文档 ID）
```json
{"code":0,"message":"success","data":[{"versionNo":2,"status":"ACTIVE","fingerprint":"9f2c…","createdAt":"2026-11-08T15:00:00","active":true},{"versionNo":1,"status":"INACTIVE","fingerprint":"3a71…","createdAt":"2026-11-01T09:00:00","active":false}]}
```
错误：`401`；`404` `文档不存在`。按 `versionNo` 倒序；他人个人文档同样返回 `404`（不暴露存在性）。

### 12.7 GET /api/knowledge/documents/{id}/versions/{versionNo} — 版本详情
鉴权：登录 ｜ 源码：`KnowledgeVersionController#version:179` ｜ 参数：路径 `id`、`versionNo`
```json
{"code":0,"message":"success","data":{"versionNo":1,"status":"INACTIVE","fingerprint":"3a71…","createdAt":"2026-11-01T09:00:00","active":false,"content":"申根签证需要：护照、照片、行程单…"}}
```
错误：`401`；`404` `文档不存在` / `版本不存在`

### 12.8 POST /api/knowledge/documents/{id}/rollback/{versionNo} — 回滚到指定版本
鉴权：登录 ｜ 源码：`KnowledgeVersionController#rollback:190` ｜ 参数：路径 `id`、`versionNo`
```json
{"code":0,"message":"success","data":{"activeVersionNo":1}}
```
错误：`400` `公共知识库文档不支持回滚`；`401`；`404` `文档不存在` / `版本不存在`。成功后服务端立即重建向量索引（`RagService.reload`）。

**版本化语义与已知限制**（表 `rag_document_version`，迁移 `V10`）：

- 同一文档任意时刻**至多一条 `ACTIVE`**；内容指纹变化时旧版置 `INACTIVE` 并**追加**新版本，旧正文与旧知识块不再删除（可审计、可回滚）。
- 向量检索只装载「`version_id` 为空（历史块）」或「所属版本为 `ACTIVE`」的块。
- **回滚不改 `rag_document.content` / `fingerprint`**（它仍是「源同步游标」）：源文件（`rag/knowledge.json`）下次变更会再产生新版本，覆盖回滚结果。
- 历史块不会被自动清理，`rag_chunk` 会随时间单调增长。
- 公共知识库文档（`user_id` 为空）任何登录用户都可读版本，但回滚返回 `400`（项目没有管理员语义）。

---

## 13. 健康（4 个）

### 13.1 POST /api/health/record — 记录健康数据
鉴权：登录 ｜ 源码：`HealthController#upsert:40` ｜ 同日重复提交为更新（upsert）
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `date` | 否 | `yyyy-MM-dd`，缺省今天 |
| `steps` | 否 | 整数 ≥0 |
| `sleepHours` | 否 | 小数 ≥0 |
| `heartRate` | 否 | 静息心率，整数 ≥0（响应字段名为 `restingHeartRate`） |
| `weight` | 否 | 小数 ≥0 |
```json
// 请求
{"date":"2026-11-08","steps":8120,"sleepHours":7.5,"heartRate":64,"weight":65.4}
// 响应
{"code":0,"message":"success","data":{"id":3,"userId":2,"date":"2026-11-08","steps":8120,"sleepHours":7.5,"restingHeartRate":64,"weight":65.4,"createdAt":"2026-11-08T08:10:00"}}
```
错误：`400` 校验失败 / `记录日期不是有效日期，请使用 yyyy-MM-dd`；`401`

### 13.2 POST /api/health/seed-demo — 生成演示数据
鉴权：登录 ｜ 源码：`HealthController#seedDemo:49` ｜ 参数：无；生成最近 7 天演示数据，已有日期不覆盖
```json
{"code":0,"message":"success","data":{"created":7}}
```
错误：`401`

### 13.3 GET /api/health/records — 最近 7 天记录
鉴权：登录 ｜ 源码：`HealthController#records:56` ｜ 参数：`limit`（默认 20，上限 200）、`offset`（默认 0），响应带 `pagination`（窗口为最近 7 天，按日期升序）
```json
{"code":0,"message":"success","data":[{"id":3,"userId":2,"date":"2026-11-08","steps":8120,"sleepHours":7.5,"restingHeartRate":64,"weight":65.4,"createdAt":"2026-11-08T08:10:00"}]}
```
错误：`401`

### 13.4 GET /api/health/summary — 健康周报摘要
鉴权：登录 ｜ 源码：`HealthController#summary:62` ｜ 参数：无
```json
{"code":0,"message":"success","data":{"latest":{"id":3,"userId":2,"date":"2026-11-08","steps":8120,"sleepHours":7.5,"restingHeartRate":64,"weight":65.4,"createdAt":"2026-11-08T08:10:00"},"avgSteps":7800,"avgSleep":7.2,"days":6,"advice":"步数表现不错，保持规律运动。睡眠时长健康。"}}
```
错误：`401`。无记录时 `latest` 为 `{}`，均值按已有数据计算。

---

## 14. 系统（1 个）

### 14.1 GET /api/system/status — 运行能力自检
鉴权：登录 ｜ 源码：`SystemController#status:37` ｜ 参数：无（不返回任何密钥）
```json
{"code":0,"message":"success","data":{"llmConfigured":true,"llmModel":"deepseek-chat","llmHint":"已接入大模型：意图识别、方案生成与文案润色走模型","amapConfigured":false,"geoProvider":"osm","geoHint":"未配置 AMAP_KEY：城市定位退回 OSM Nominatim，国内网络可能较慢或失败","timezone":"Asia/Shanghai","ocrConfigured":false,"ocrHint":"未配置 OCR：支持 PDF 电子发票与粘贴文本；图片需设置 TESSERACT_CMD","pendingPlanStore":"database","rateLimitEnabled":true,"llmQuotaUsed":3,"llmQuotaLimit":50}}
```
错误：`401`。`llmQuotaUsed` / `llmQuotaLimit` 仅在 `travel.llm.quota-daily-limit > 0` 且已登录时出现。

---

## 15. 渠道（3 个）

### 15.1 POST /api/channels/webhook — 通用 Webhook 入口
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

### 15.2 GET /api/channels/wechat — 微信公众号接入验证
鉴权：微信签名（`sha1(sort(token, timestamp, nonce)) == signature`）｜ 源码：`WeChatOfficialAccountController#verify:66`
| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `signature` / `timestamp` / `nonce` / `echostr` | 是 | 微信服务器握手参数 |
响应：纯文本 `echostr`（校验通过原样返回）。
错误：`403` `wechat channel disabled`（未配置 `WECHAT_OA_TOKEN`）/ `signature mismatch`；`400` `missing params`

### 15.3 POST /api/channels/wechat — 微信公众号消息推送
鉴权：微信平台（明文模式；渠道关闭时拒绝）｜ 源码：`WeChatOfficialAccountController#receive:85` ｜ 请求体：微信 XML（`text` 消息或 `event/subscribe`）
```xml
<xml><ToUserName><![CDATA[gh_xxx]]></ToUserName><FromUserName><![CDATA[oUser123]]></FromUserName><CreateTime>1762598284</CreateTime><MsgType><![CDATA[text]]></MsgType><Content><![CDATA[你好]]></Content></xml>
```
响应：未配置客服消息时返回被动回复 XML（`MsgType=text`，内容超 2000 字截断）；配置了 `appid+secret` 时立即返回 `success` 并异步走客服消息；非文本/非关注事件返回空串。
错误：`403`（渠道关闭）；XML 解析失败或字段缺失返回 `200` + 空串。

---

## 16. 接口总表

| # | 方法 | 路径 | 鉴权 | 说明 | 源码 |
| --- | --- | --- | --- | --- | --- |
| 1 | POST | `/api/auth/register` | 免登录 | 注册 | `AuthController#register:27` |
| 2 | POST | `/api/auth/login` | 免登录 | 登录 | `AuthController#login:35` |
| 3 | GET | `/api/auth/me` | 登录 | 恢复登录态 | `AuthController#me:47` |
| 4 | POST | `/api/auth/logout` | 免登录 | 登出 | `AuthController#logout:58` |
| 5 | POST | `/api/access-ticket` | 登录 | 签发短期只读票据 | `AccessTicketController#issue:21` |
| 6 | GET | `/api/trips` | 登录 | 行程列表 | `TripController#list:40` |
| 7 | GET | `/api/trips/map` | 登录 | 足迹地图坐标 | `TripController#map:50` |
| 8 | POST | `/api/trips` | 登录 | 创建行程 | `TripController#create:56` |
| 9 | GET | `/api/trips/{id}` | 登录 | 行程详情 | `TripController#get:62` |
| 10 | PUT | `/api/trips/{id}` | 登录 | 更新行程 | `TripController#update:68` |
| 11 | DELETE | `/api/trips/{id}` | 登录 | 删除行程 | `TripController#delete:75` |
| 12 | PUT | `/api/trips/{id}/status` | 登录 | 确认/取消/恢复 | `TripController#updateStatus:83` |
| 13 | GET | `/api/trips/{id}/itinerary` | 登录 | 行程项列表 | `TripController#itinerary:92` |
| 14 | POST | `/api/trips/{id}/itinerary` | 登录 | 新增行程项 | `TripController#addItem:106` |
| 15 | GET | `/api/trips/{id}/budget` | 登录 | 预算执行对账 | `TripController#budget:100` |
| 16 | GET | `/api/trips/{id}/guidebook.pdf` | 登录 | 路书 PDF 导出 | `TripController#guidebook:118` |
| 17 | PUT | `/api/itinerary/{id}` | 登录 | 修改行程项 | `ItineraryController#update:27` |
| 18 | DELETE | `/api/itinerary/{id}` | 登录 | 删除行程项 | `ItineraryController#delete:40` |
| 19 | POST | `/api/agent/chat` | 登录 | 同步对话 | `AgentController#chat:39` |
| 20 | GET | `/api/agent/stream` | 登录/票据 | SSE 流式对话 | `AgentController#stream:51` |
| 21 | POST | `/api/agent/stream` | 登录 | SSE 流式对话（表单） | `AgentController#streamPost:75` |
| 22 | POST | `/api/agent/confirm` | 登录 | 确认提交方案 | `AgentController#confirm:117` |
| 23 | POST | `/api/agent/cancel` | 登录 | 取消方案 | `AgentController#cancel:124` |
| 24 | POST | `/api/agent/undo` | 登录 | 撤销上一次修改 | `AgentController#undo:131` |
| 25 | GET | `/api/agent/plans/{planId}` | 登录 | 取回待确认方案 | `AgentController#pending:139` |
| 26 | POST | `/api/agent/conversation/clear` | 登录 | 清空会话 | `AgentController#clearConversation:146` |
| 27 | GET | `/api/trips/{tripId}/expenses` | 登录 | 支出列表 | `ExpenseController#list:30` |
| 28 | POST | `/api/trips/{tripId}/expenses` | 登录 | 记一笔 | `ExpenseController#add:41` |
| 29 | GET | `/api/expenses/overview` | 登录 | 记账总览 | `ExpenseController#overview:55` |
| 30 | DELETE | `/api/expenses/{id}` | 登录 | 删除支出 | `ExpenseController#delete:61` |
| 31 | POST | `/api/trips/{tripId}/photos` | 登录 | 上传照片 | `PhotoController#upload:28` |
| 32 | GET | `/api/trips/{tripId}/photos` | 登录 | 照片列表 | `PhotoController#list:36` |
| 33 | GET | `/api/photos/{id}/raw` | 登录/票据 | 读取原图 | `PhotoController#raw:52` |
| 34 | DELETE | `/api/photos/{id}` | 登录 | 删除照片 | `PhotoController#delete:70` |
| 35 | GET | `/api/reminders` | 登录 | 提醒板 | `ReminderController#board:22` |
| 36 | POST | `/api/reminders/schedule/{id}/dismiss` | 登录 | 忽略日程提醒 | `ReminderController#dismissSchedule:28` |
| 37 | POST | `/api/reminders/trip/{id}/dismiss` | 登录 | 忽略出发提醒 | `ReminderController#dismissTrip:35` |
| 38 | GET | `/api/memos` | 登录 | 备忘列表 | `MemoController#list:32` |
| 39 | POST | `/api/memos` | 登录 | 新增备忘 | `MemoController#add:41` |
| 40 | POST | `/api/memos/{id}/toggle` | 登录 | 切换完成状态 | `MemoController#toggle:47` |
| 41 | DELETE | `/api/memos/{id}` | 登录 | 删除备忘 | `MemoController#delete:53` |
| 42 | GET | `/api/memos/open-count` | 登录 | 未完成备忘数量 | `MemoController#openCount:60` |
| 43 | GET | `/api/schedule` | 登录 | 日程列表 | `ScheduleController#list:39` |
| 44 | POST | `/api/schedule` | 登录 | 新增日程 | `ScheduleController#add:48` |
| 45 | POST | `/api/schedule/{id}/toggle` | 登录 | 切换完成状态 | `ScheduleController#toggle:59` |
| 46 | DELETE | `/api/schedule/{id}` | 登录 | 删除日程 | `ScheduleController#delete:65` |
| 47 | POST | `/api/office/mail/classify` | 登录 | 邮件分类 | `OfficeController#classifyMail:58` |
| 48 | GET | `/api/office/mail` | 登录 | 邮件列表 | `OfficeController#mailList:76` |
| 49 | POST | `/api/office/minutes/generate` | 登录 | 生成会议纪要 | `OfficeController#generateMinutes:91` |
| 50 | GET | `/api/office/minutes` | 登录 | 会议纪要列表 | `OfficeController#minutesList:104` |
| 51 | POST | `/api/office/invoice/extract` | 登录 | 文本提取发票 | `OfficeController#extractInvoice:118` |
| 52 | GET | `/api/office/invoice` | 登录 | 发票列表 | `OfficeController#invoiceList:137` |
| 53 | POST | `/api/office/invoice/upload` | 登录 | 上传发票文件 | `OfficeController#uploadInvoice:151` |
| 54 | POST | `/api/office/invoice/{id}/expense` | 登录 | 发票一键记账 | `OfficeController#invoiceToExpense:192` |
| 55 | POST | `/api/office/report/generate` | 登录 | 生成今日日报 | `ReportController#generate:26` |
| 56 | GET | `/api/office/report` | 登录 | 日报列表 | `ReportController#reportList:32` |
| 57 | POST | `/api/learning/generate` | 登录 | 生成学习产物 | `LearningController#generate:52` |
| 58 | GET | `/api/learning/artifacts` | 登录 | 学习产物列表 | `LearningController#artifacts:70` |
| 59 | DELETE | `/api/learning/artifacts/{id}` | 登录 | 删除学习产物 | `LearningController#delete:82` |
| 60 | POST | `/api/learning/knowledge/add` | 登录 | 搭建知识库 | `LearningController#addKnowledge:99` |
| 61 | POST | `/api/learning/knowledge/search` | 登录 | 知识库检索 | `LearningController#searchKnowledge:133` |
| 62 | POST | `/api/health/record` | 登录 | 记录健康数据 | `HealthController#upsert:40` |
| 63 | POST | `/api/health/seed-demo` | 登录 | 生成演示数据 | `HealthController#seedDemo:49` |
| 64 | GET | `/api/health/records` | 登录 | 最近 7 天记录 | `HealthController#records:56` |
| 65 | GET | `/api/health/summary` | 登录 | 健康周报摘要 | `HealthController#summary:62` |
| 66 | GET | `/api/system/status` | 登录 | 运行能力自检 | `SystemController#status:37` |
| 67 | POST | `/api/channels/webhook` | 渠道密钥 | 通用 Webhook | `WebhookChannelController#handle:43` |
| 68 | GET | `/api/channels/wechat` | 微信签名 | 公众号接入验证 | `WeChatOfficialAccountController#verify:66` |
| 69 | POST | `/api/channels/wechat` | 微信平台 | 公众号消息推送 | `WeChatOfficialAccountController#receive:85` |
| 70 | GET | `/api/knowledge/documents/{id}/versions` | 登录 | 知识库版本列表（第九批） | `LearningController#versions:168` |
| 71 | GET | `/api/knowledge/documents/{id}/versions/{versionNo}` | 登录 | 版本详情含正文（第九批） | `LearningController#version:179` |
| 72 | POST | `/api/knowledge/documents/{id}/rollback/{versionNo}` | 登录 | 回滚到指定版本（第九批） | `LearningController#rollback:190` |

---

## 17. 接口总数统计

### 17.1 按 Controller（与源码映射注解逐一核对）

| Controller | 接口数 | 源码文件 |
| --- | --- | --- |
| `AuthController` | 4 | `controller/AuthController.java` |
| `AccessTicketController` | 1 | `controller/AccessTicketController.java` |
| `TripController` | 11 | `controller/TripController.java` |
| `ItineraryController` | 2 | `controller/ItineraryController.java` |
| `AgentController` | 8 | `controller/AgentController.java` |
| `ExpenseController` | 4 | `controller/ExpenseController.java` |
| `PhotoController` | 4 | `controller/PhotoController.java` |
| `ReminderController` | 3 | `controller/ReminderController.java` |
| `MemoController` | 5 | `controller/MemoController.java` |
| `ScheduleController` | 4 | `controller/ScheduleController.java` |
| `OfficeController` | 8 | `controller/OfficeController.java` |
| `ReportController` | 2 | `controller/ReportController.java` |
| `LearningController` | 8 | `controller/LearningController.java`（含内嵌 `KnowledgeVersionController`） |
| `HealthController` | 4 | `controller/HealthController.java` |
| `SystemController` | 1 | `controller/SystemController.java` |
| `WeChatOfficialAccountController` | 2 | `channel/WeChatOfficialAccountController.java` |
| `WebhookChannelController` | 1 | `channel/WebhookChannelController.java` |
| **合计** | **72** | 15 个 controller 包文件 + 3 个 channel 包接口 |

### 17.2 按业务模块

| 模块 | 章 | 数量 |
| --- | --- | --- |
| 认证与票据 | §2 | 5 |
| 行程 | §3 | 11 |
| 行程项 | §4 | 2 |
| Agent 对话与方案 | §5 | 8 |
| 记账 | §6 | 4 |
| 照片 | §7 | 4 |
| 提醒 | §8 | 3 |
| 备忘 | §9 | 5 |
| 日程 | §10 | 4 |
| 办公 | §11 | 10 |
| 学习与知识库 | §12 | 8 |
| 健康 | §13 | 4 |
| 系统 | §14 | 1 |
| 渠道 | §15 | 3 |
| **合计** | — | **72** |

### 17.3 自检结论与 README 差异

- 源码映射注解统计（`node .docgen/count-endpoints.mjs`）：**69（controller）+ 3（channel）= 72**，与本文第 16 章清单逐条一致，无遗漏、无臆造（第九批新增知识库版本化 3 个接口）。
- 与 README 第 5 节「REST API 一览」的差异：README 清单为 **65 个**，**未列出**以下 4 个已上线接口：
  1. `POST /api/agent/stream`（结构化规划表单的 SSE 入口，`AgentController#streamPost:75`）
  2. `GET /api/memos/open-count`（未完成备忘数，`MemoController#openCount:60`）
  3. `GET /api/channels/wechat`（公众号接入验证，`WeChatOfficialAccountController#verify:66`）
  4. `POST /api/channels/wechat`（公众号消息推送，`WeChatOfficialAccountController#receive:85`）
- README §5 将微信公众号的两个接口只写在 §4.10 的渠道表格里，未进 REST 清单；其余路径与参数与源码一致。

---

*文档核验方式：逐条对照 `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` 与对应 Controller 方法、DTO、`GlobalExceptionHandler`、`SecurityConfig`；所有示例字段均来自真实 DTO/实体字段。*
