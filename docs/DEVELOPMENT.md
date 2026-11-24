# 开发与配置指南

## 环境变量

仓库内不保存任何密钥，以下变量一律通过环境变量注入，未配置时的降级行为一并说明。

| 变量 | 是否必需 | 未配置时的行为 |
| --- | --- | --- |
| `JWT_SECRET` | 生产必需 | 启动时生成一次性随机密钥并打印 WARN：本地开箱即用，但**服务重启后所有已签发 token 失效**；生产不配置则每次发布都会强制全体用户重新登录 |
| `LLM_API_KEY` | 可选 | Agent 全部走规则规划器：意图识别、方案生成、文案润色使用内置模板与规则，主流程可跑，效果弱于模型 |
| `AMAP_KEY` | 可选 | 后端城市 POI 走「大模型生成坐标 + OSM Nominatim」兜底路径，速度较慢；类型为高德「Web 服务」Key |
| `VITE_AMAP_KEY` / `VITE_AMAP_SECURITY_CODE` | 行程地图必需 | 高德「Web 端(JS API)」Key 与配套安全密钥，写入 `travel-agent-web/.env.local`；未配置时行程地图显示配置提示 |
| `APP_TIMEZONE` | 可选（已设默认） | 默认 `Asia/Shanghai`；若部署在其他时区需显式覆盖，否则「今天/明天」会按上海时间计算 |
| `travel.photos.dir` | 可选（已设默认） | 行程照片落盘目录，默认 `data/photos`（已 gitignore）；换对象存储时只需替换 `PhotoStorage` |

本地开发建议把密钥写进 `travel-agent-server/.env.local`（已 gitignore），用 `.\run-dev.ps1` 启动：
脚本会注入其中的环境变量，并在缺少 `JWT_SECRET` 时自动生成一个写回该文件，避免每次重启都被登出。

生成 JWT 密钥：

```bash
openssl rand -base64 48
```

密钥要求：Base64 编码，解码后长度 ≥ 32 字节（HS256 最小长度）。长度不足会在启动时直接报错并提示重新生成，
非法 Base64 同样会在启动时报错——这是有意设计的快速失败，避免线上用弱密钥签发 token。

PowerShell 设置方式：

```powershell
$env:JWT_SECRET = "<base64>"
$env:LLM_API_KEY = "sk-xxx"
```

## 本地开发

```bash
# 后端（8080）
cd travel-agent-server && mvn spring-boot:run

# 前端（5173，dev server 通过 vite proxy 转发 /api 到 8080）
cd travel-agent-web && npm run dev
```

> Windows 提示：如果 `npm` 报 `Cannot run a document in the middle of a pipeline`，说明 PATH 里
> 有同名残留文件遮蔽了真正的 npm（例如 `D:\npm` 是 Node 安装包里的 bash 脚本）。
> 临时用 `npm.cmd run dev`，彻底解决就删掉那个残留文件并重开终端。

数据库默认使用 H2 文件库 `travel-agent-server/data/travel-agent.mv.db`（已在 .gitignore 中，不会提交）。
需要重置本地数据时删除该文件即可，重启会重新建表并写入演示数据。

## 测试

```bash
cd travel-agent-server && mvn test          # 后端集成测试
cd travel-agent-web && npm test             # 前端单元测试
cd travel-agent-web && npm run typecheck    # 仅类型检查
```

当前规模（源码静态计数，实际以 CI 输出为准）：后端 465 个 `@Test`（110 个测试文件；认证/越权、Agent 计划与撤销、
工具层契约、RAG 过滤与引用、日期映射、意图路由、冲突检查、记账对账、时区、微信渠道、POST 流式端点、迁移基线、并发确认、
P1 旅行闭环），前端 216 个用例（agent store、SSE 客户端、axios 401 分流、足迹数据、规划表单、浏览器通知、高德地图 Provider、
地图标记、票据、知识库/回顾契约），e2e 5 个（Playwright：登录建行程路书、PWA 资源、结构化表单攻略/从零闭环）。

> 在 Linux/macOS 或 CI 上 `mvn test` 可全绿；**Windows 本地若出现 `Could not self-attach to current VM`**，
> 是 Mockito/ByteBuddy 自附加被本机策略拦截（与代码无关），在 CI 或容器里跑测试即可。

约定：

- 后端测试统一继承 `IntegrationTestSupport`：真实 Spring 上下文 + MockMvc（挂载安全过滤器链）+ `test` profile（内存 H2、关闭 LLM 与高德）。
- 测试不允许访问网络或真实大模型；用户名使用 `uniqueUsername()` 保证彼此隔离。
- 错误语义是测试契约的一部分：参数错误 400、未登录 401、无权限 403、资源不存在 404、数据冲突 409、兜底 500 不泄露内部信息。
- 前端测试默认运行在 node 环境（覆盖 store / API 客户端等纯逻辑）。若需渲染组件，在文件顶部加 `// @vitest-environment jsdom`；
  jsdom 依赖 `require(ESM)`，需要 Node ≥ 20.19，或在 Node 20.17 上保持默认的 node 环境。

`.e2e/` 下的脚本是手工端到端验证，需要本地同时启动后端与前端：

```bash
cd travel-agent-server && mvn spring-boot:run
node .e2e/e2e.mjs
```

## 代码结构

写第一行代码前先看 **[docs/guide/code-structure.md](guide/code-structure.md)**：分层与依赖方向、16 个包的职责、
「我要加 X 放哪里」决策表、命名约定与测试组织。以下几条是评审时的硬性约束：

- **单类超过 500 行或构造函数超过 12 个依赖** → 拆内聚协作类；拆分优先新增类，不改公开方法签名。
- 编排类不写 JSON 解析/网络细节；解析类不写缓存；缓存类不写业务判定（参考第十一批的
  `AgentEngine`、`OnlineCityService` 拆分）。
- 公共规则只保留一个实现：城市名匹配 `util.CityNames`、高德坐标解析
  `CoordinateTransform.parseGcj02Location`、配置读取 `*Properties#usable()`（禁止新增散落的 `@Value`）。
- 写操作唯一入口仍是 `ExecutorAgent.commit`；工具层永远只读。

## CI

`.github/workflows/ci.yml` 在 push 到 main 与所有 PR 上运行：

| Job | 命令 |
| --- | --- |
| backend | `mvn -B verify` |
| frontend | `npm ci && npm run typecheck && npm test && npm run build` |

## 关键约定

**错误语义**：控制器不自行拼装 `ApiResponse.error`，而是抛 `BusinessException(code, message)`，
由 `GlobalExceptionHandler` 统一转换成对应的 HTTP 状态码，且 `code` 与 HTTP 状态保持一致。
Agent 内部抛出的 `BusinessException` 也会原样透出给用户（不再包装成"服务端异常"），
而真正的未知异常只返回通用提示、细节进日志。

**工具层只读**：`AgentTool.readOnly()` 默认为 true，`ToolRegistry.register()`（构造期注入与
MCP 桥接共用的唯一注册口）会拒绝注册写操作工具；任何"会改数据"的能力都必须实现为
`AgentPlan` 的操作项，经确认后由 `ExecutorAgent.commit` 落地。
HIGH 风险只表示"方案需要显式确认"（超预算），不是"抛异常报错"。

**MCP 外部工具**：`travel.mcp.servers`（`MCP_ENABLED` 开关）由 `McpToolBridge` 在启动期接入，
传输层二选一：streamable HTTP（`url`）或本机子进程 stdio（`command`，Windows 下 npx 需
`cmd /c` 前缀）。注册名固定 `mcp_{server}_{tool}` 且默认只读；工具描述会拼上从 inputSchema
提取的参数提示供 LLM 生成参数。`IntentAgent` 只在 LLM 路径产出 `INVOKE_TOOL`，且必须命中
已注册的 mcp_* 工具（否则拦回规则引擎）；规则路径永远不产出该意图，保证离线行为可预期。
单个 server 接入失败只告警，**不允许**让 MCP 故障阻断应用启动。

**多渠道入口**：外部渠道一律复用 `AgentEngine.handle(ctx)`，不要绕开引擎另写业务逻辑。
Telegram 走长轮询（`SmartLifecycle`，token 为空直接不启动）；每个 chat 对应本地账号
`tg{chatId}`（自动开通，密码随机不外发）；Webhook（`/api/channels/webhook`）密钥即凭证，
恒时比较（`MessageDigest.isEqual`），body 指定已存在的本地 username、不自动注册。
渠道内产生待确认方案时只播报 planId 提示去网页确认——**确认/撤销等写操作只保留网页一个入口**。

**路书导出**：`TripGuidebookService` 先渲染 HTML 再转 PDF（flying-saucer），中文字体探测顺序
classpath:fonts/cjk.* → Windows → Linux/macOS，找不到一律抛业务错误（不导出乱码）。
模板用 `String.formatted` 时注意 `%` 转义（正文是参数不是模板，安全；模板内字面 `%` 要写 `%%`）。

**事务边界**：`ExecutorAgent.commit` 是唯一落库入口，标注 `@Transactional`；
行程变更与操作历史必须同生共死，否则用户会「改成功但撤不回来」。
`OperationHistoryService` 不允许吞异常；界面上的手工增删改通过 `withHistory(...)` 把"变更 + 记录快照"放进同一事务。

**撤销语义**：历史分三类——`AGENT_COMMIT`（方案确认）、`MANUAL_EDIT`（手工增删改）、`TRIP_CREATE`（Agent 新建行程）。
`undoLatest` 遇到 `TRIP_CREATE` 会删除整个行程（级联清理行程项与账单），而不是只清空条目。
新增会改变行程的入口时，必须同时写历史，否则"撤销"会跨过这次改动回滚到更早的快照。

**日期映射**：用户说「今天/明天/后天」时用 `MessageParser.resolveTripDay` 映射到行程天——
落在行程区间内直接用；落在区间外按「当前天 + 偏移」顺推并返回 `outsideTrip=true`，
由 PlannerAgent 在回复里说明「已按 Day N 处理」。**不要再用绝对日期 + clamp**（会出现"说明天却改了最后一天"）。
天气查询使用映射后那一天的日期，保证"看到什么天气"与"改了哪一天"一致。

**RAG 城市过滤**：指定城市检索时保留「该城市文档 + 通用/未标注城市文档」（`InMemoryVectorStore.matchesCity`）。
否则"注意事项"这类通用知识在有行程时会被全部过滤掉，目录外城市更是直接空结果。

**记账对账**：`ExpenseService.overview` 必须满足「各分项之和 == totalSpent」，
行程已删除的遗留账单归入 `tripId=0 / 已删除的行程` 分组（前端该分组不可点击跳转）。

**预算口径**：计划成本 = 行程项 `cost` 合计、实际 = 记账合计、预算 = `trip.budget`；
对外统一由 `BudgetService.execution(...)` 产出（`GET /api/trips/{id}/budget`），
不要在别处再拼一套。分类映射：酒店→住宿、餐饮→餐饮、交通→交通、景点→门票。

**确认版本**：待确认方案记录 `ItineraryService.planRevision(...)`（日期 + 预算 + 全部条目指纹），
`confirm` 时必须与当前行程一致，否则拒绝并提示重新生成——防止多标签页/设备按过期 Diff 覆盖。

**意图路由**：添加日程要求「排期名词 + 明确时间点（或排期动词）」同时成立，
不能只靠关键词（`parseScheduleRequest` 对任何非空文本都返回非 null）。
兜底意图是 `CHITCHAT`（返回能力提示），不要贸然返回 `PLAN_TRIP`，否则"没听懂"会变成"暂不支持该目的地"。

**会话记忆**：`conversationId` 由前端生成（sessionStorage，跨页面切换保持）并随请求携带，
服务端一切查询都以 `userId` 隔离——**永远不要信任客户端传的会话标识来做归属判断**。
`AgentEngine` 在 UNDERSTAND 之前载入历史（`loadConversation`，先载后存，history 不含本轮），
历史只喂给 `IntentAgent` 的 LLM 路径，**规则路径必须保持无状态**（行为可预期、测试离线可跑）。
存取失败按单轮对话继续，不许挡主流程；内容入库前截断 1000 字。
「新对话」= 前端换新 id + `POST /api/agent/conversation/clear` 清服务端历史。
给提示词拼接用户原文时**不要用 `String.formatted`**（用户消息可能含 `%`），直接字符串拼接。

**方案文案润色**：模板先保证事实，`PlanNarrationService.polish` 只做语言组织；
输出做长度与关键金额校验，不达标回退模板。润色走 8s 独立读超时
（`LlmClient.chat(messages, temperature, readTimeoutMs)` 重载），辅助能力不许挂全局 30s 超时。

**时区**：`TimeZoneConfig` 在启动时把 JVM 默认时区固定为 `travel.timezone`（默认 `Asia/Shanghai`）。
`LocalDate.now()` 依赖它，新增时间相关逻辑时不要绕开。

**提醒**：不引入后台调度器——`ReminderService.board(userId, now)` 按当前时间实时计算，
前端轮询；`now` 可注入便于测试。规则常量（提前 30 分钟、提前一天 09:00、事后 6 小时宽限）集中在
`ReminderService`；忽略写入 `reminder_dismissed_at` / `departure_reminder_dismissed_at`，不要把"已读"
存在前端本地（否则多端不一致）。

**重复日程**：`ScheduleItem.recurrence`（NONE/DAILY/WEEKLY/MONTHLY）+ `repeatUntil`，`date` 是首次发生日期。
"下一次发生"统一用 `Recurrence.nextOccurrence(...)`（会跳过今天已过的时刻），不要在别处自己加天数。
勾选完成对重复日程表示"结束整个系列"。

**浏览器通知**：只在「已授权 + 页面 hidden + 本会话未通知过」时发（`lib/reminderNotify.ts`），
权限必须由用户点击按钮触发 `Notification.requestPermission()`。

**旅行闭环（P1）**：行程项状态只能经 `ItineraryService.updateStatus` 推进（`PENDING/IN_PROGRESS/COMPLETED/SKIPPED`）；
完成写 `startedAt/completedAt/visitedAt` 并追加 `CHECK_IN` 事件，状态变更必须与操作历史同事务。自动重规划只允许调整
`PENDING` 条目。回顾口径见 `TripReviewService`：完成率不含 `SKIPPED`，`visitedPlaces/actualRoute` 只认 `visitedAt`。
RAG 引用在 `plan.extras.citations`、`AgentResult.citations`、SSE `rag_result` 三处同构，禁止模型编造 documentId/version/score。

**越权保护**：所有行程相关接口先做 `tripService.getOwned(tripId, userId)`；
批量操作（`applyOperations`）还会校验每个条目确实属于该行程。

**照片存储**：文件落在 `travel.photos.dir`（默认 `data/photos`），库里只存元数据（`trip_photo`）。
读写都要先过行程归属校验；删除行程会级联清理照片记录与磁盘文件。
配额：单张 ≤10MB、单行程 `travel.photos.max-per-trip`（默认 200 张）、单用户 `max-bytes-per-user`（默认 500MB）。

**照片类型判定**：必须以文件头为准（`util/ImageSignatures`），只接受 jpg/png/webp/gif/bmp/heic 白名单。
**不要**退回 `contentType.startsWith("image/")`：SVG 也满足该前缀，且 SVG 可内嵌脚本，
存下来后直接打开原图链接就是同源存储型 XSS。存储扩展名与响应 `Content-Type` 一律由服务端推导，
不回显客户端声明；原图响应额外带 `X-Content-Type-Options: nosniff` 与 `Content-Security-Policy`。
落盘顺序同样是约定：**先写元数据再落盘**，否则插入失败会留下回收不到的孤儿文件。

**URL 凭证**：EventSource 与 `<img>` 无法自定义请求头，只能把凭证放进查询串。
这里传的是 `AccessTicketService` 签发的 10 分钟只读票据（scope `media-read`，密钥由 JWT 密钥派生），
**不是登录 JWT**——登录凭证出现在 URL 里会进入访问日志与浏览器历史。
前端 `api/ticket.ts` 负责缓存与并发合并，新增同类场景（又一个 `<img>` / EventSource 入口）请复用它。

**登录态**：浏览器走 httpOnly Cookie（`AuthCookieService`，SameSite=Lax，maxAge 与 token 对齐），
**前端不持久化 token**——刷新后靠 `GET /api/auth/me` 恢复 user，`Protected` 在 `authChecked` 前不做跳转判断。
Authorization 头继续保留给非浏览器客户端；新增接口不需要关心凭证来源。
UI 登出必须走 `api/auth.ts` 的 `signOut()`（先清服务端 Cookie 再清本地状态），不要直接调 store 的 `logout()`。

**天气数据源**：`WeatherService.forecast(city, date)` 是唯一入口，返回 `WeatherLookup`——要么可用真实预报，
要么「不可用 + 原因」，**没有第三种猜出来的天气**。产品路径不提供模拟兜底：默认走 Open-Meteo
（内置城市用目录坐标均值，目录外走其地理编码），网络失败 / 超出 16 天窗口 / 城市无法定位即不可用。
依赖天气的修改（消息含雨/天气等）在不可用时抛 `BusinessException(503)` 并明确拒绝；普通规划只写「未做天气校验」。
模拟天气只在 `WEATHER_SIMULATION=true`（仅测试 / 离线演示）下出现，且状态接口自报 `simulated`；
旧的 `mentionRain`「提到雨就模拟降雨」行为已删除。新增天气相关功能不要绕过该入口。

**Flyway**：`db/migration/V*__*.sql` 是 schema 变更的唯一通道（存量库已 baseline 在版本 1）。
`CREATE INDEX IF NOT EXISTS` 在 MySQL 不可用，索引交给实体 `@Index` + ddl-auto 补齐；
脚本要同时兼容 H2（MODE=MySQL）与 MySQL 8。`ddl-auto` 暂留 `update`，全面审计后才切 `validate`。

**校验**：DTO 使用 `jakarta.validation` 注解，控制器参数加 `@Valid`；
「格式正确但值非法」的日期（如 2026-02-30）通过 `util/Dates` 统一转 400。

**前端分包**：路由全部懒加载（`React.lazy`），重依赖（echarts / maplibre / antd）在 `vite.config.ts` 中显式分 chunk。
页面上新增重型组件时请用 `lazy` + `Suspense`，避免回到单包 3MB。行程地图的高德 JS API 由 `amapLoader.ts`
按页面从官方地址加载，2D/3D 切换共享同一脚本实例；足迹地图仍使用 MapLibre 球体投影，底图默认用高德栅格
（`FootprintMap/basemap.ts`），`VITE_FOOTPRINT_BASEMAP=osm` 可退回原 OSM/OpenFreeMap。

**地图组件**：`.fp-map` / `.maplibregl-map` 是 maplibre 的容器，maplibre 会给它加 `position: relative`，
其样式在懒加载后才注入且优先级不低——给容器设尺寸时不要用 `absolute + inset`，**必须显式写 height**，否则容器塌成 0 高、地图完全不渲染（已踩过一次）。
足迹地图的球体投影依赖 maplibre v5+，代码里做了运行时探测，旧版本自动退回平面 3D。
坐标约定：业务数据存 WGS-84；高德底图/JS API 用 GCJ-02，只在渲染前转换（`src/lib/coords.ts` 与后端
`util/CoordinateTransform.java` 同一套公式）。不要在两个坐标系之间直接混用坐标，否则国内会偏几百米。

### V2.2 关键不变量（自主 Agent / 追踪 / 确认后同步）

**追踪不反噬主流程**：`AgentTraceService` 的所有写入走 `TransactionTemplate(PROPAGATION_REQUIRES_NEW)`，
且 **try/catch 必须在事务边界之外**。不要改成方法级 `@Transactional`——方法内 catch 住内层事务异常后，
外层代理提交一个已被标记 rollback-only 的事务会抛 `UnexpectedRollbackException`，反而把追踪故障放大成业务故障。
追踪写失败只降级（`runId=null` 时后续记录静默跳过），绝不能让用户看到"处理失败"。
新增追踪落库点一律复用它，不要另起事务写法。

**自主循环必须能「什么都没做」**：`AgentToolLoop.run` 的 `completed=false` 语义是「没拿到可用结论」，
调用方（`PlannerAgent.researchFacts`）必须返回空清单并继续走既有固定调用路径。
不要因为新增了循环就让「无 LLM / 配额耗尽 / 输出非法」变成规划失败或变慢。
循环的每步之间检查 `ctx.isCancelled()`，**不要 `Thread.interrupt()`**（池线程会被污染）；
工具只能经 `ToolInvoker` 调用（沿用只读门禁 + 审计 + 追踪），循环里不许直接 `tool.execute()`。

**确认门是双指纹**：`planRevision`（生成方案时的**行程状态**）拦「方案生成后行程被别的入口改过」；
`contentHash`（方案**内容本身**的 SHA-256）拦「用户确认的内容 ≠ 后台现存内容」。
两者是叠加关系：`contentHash` 为空（历史行 / 旧客户端）时退化为只走 `planRevision`，**不要**把缺失当成校验失败。
claim（PENDING→CONFIRMING）语义与 TTL 30 分钟都不许动。

**确认后同步的口径红线**（`PlanExecutionService`）：
`calendar` 模块按 `schedule_item.source_key = plan-sync:{planId}:day:{n}` 幂等创建，行程删除时级联清理；
`budget` 模块**只记计划快照**（预算上限 + 计划成本合计），绝不自动生成 `Expense`——估算不能变成"已消费"；
`footprint` 模块**只标计划态**，绝不把未来行程写成"已到访"。
模块级失败不回滚已保存的行程（用户需求要先满足），失败模块靠 `POST /api/agent/executions/{id}/retry` 单独重试，
成功项靠幂等键保证不重复创建。`plan_execution.plan_id` 唯一 = 同一方案只有一份逻辑执行。

**任务图是确定性编排（第十五批）**：依赖边由引擎写死（`INTENT → PLAN → {PREVIEW ‖ REVIEW} → (REPLAN → REVIEW)*`），
`Coordinator` 只执行图，**运行期不改图、不让 LLM 选择下一个任务**。前置任务不是 `SUCCEEDED` 时
`AgentTraceService.startTask` 抛 `TaskDependencyException`：Coordinator 落一条 `CONFLICT` 任务（没有执行体）
并拒绝执行 body——**不要**改成「阻塞等待依赖」（会占死线程池）。重派一律新行
（`attempt+1`、`replacesTaskId=旧行`），旧行保留失败证据；同一逻辑工作只允许一份可用结果。
并行只允许发生在无共享可变状态的任务间：`AgentContext.currentTaskId` 是 ThreadLocal，
新增并行任务前先确认它不写共享状态（尤其不要并行写库/发 SSE）。

## 设计系统（方向 B：内容优先）

**令牌**：`travel-agent-web/src/styles/tokens.css` 顶部的 `:root` 是唯一来源。
新名字（`--bg` / `--surface` / `--border` / `--text` / `--text-2` / `--text-3` / `--accent` /
`--ok` / `--warn` / `--danger` / `--r-*` / `--sp-*`）是主用名；
旧名字（`--ink` / `--line` / `--blue` / `--lg-bg` / `--lg-border` / `--lg-blur` / `--lg-shadow` /
`--radius-*` / `--shadow-*`）保留为**别名**，指向同一批值——所以 2000+ 行历史样式能一次性换肤。
新增样式请用新名字。

**材质**：不再使用毛玻璃与极光。`--lg-bg` / `--lg-border` / `--lg-shadow` 已映射为平面卡片值，
全站 `backdrop-filter: none`，`.aurora` 与 `.lg-sweep::after` 已停用。
想恢复玻璃效果必须把这几处一起改回来，只改一个变量不会生效。

**地图容器必须给确定高度**：`.fp-canvas` / `.fp-map` 用的是 `height: 100%`，而百分比高度需要确定的父高度。
父容器若只有 `flex: 1` 或 `min-height`，解析会退化成 `auto`，maplibre 容器塌成 0 高、再被 `overflow: hidden` 裁掉
（表现为整块地图空白，DOM 里能看到 canvas 但尺寸为 0）。首页 `.hg-map` 因此写成 `flex: none; height: 380px`，
**改布局时不要退回 flex 高度**。

**响应式**：≥1080px 侧栏 232px；720–1080px 收成 76px 图标栏（隐藏 `.sb-label`）；<720px 换成底部标签栏 `.app-tabbar`。
文件里 `@media (max-width: 900px)` 那段"侧栏变横向顶栏"的历史规则与新方案冲突，
已在文件末尾的新媒体查询里逐条覆盖（`position` / `flex-direction` / `align-items` / `.sb-*` 都要显式重置）。
**新增窄屏规则请写在文件末尾**，否则会被旧规则抢先生效。

**antd**：`main.tsx` 的 `ConfigProvider` token 与上面是同一套色值（主色 `#1f6feb`、圆角 8、边框 `#e4e6eb`）。
改色板时两处要一起改，避免 antd 组件与自定义样式出现两种蓝。

**暗色主题**：`[data-theme='dark']` 只覆盖新令牌这一层，`html` 上的属性由 `store/themeStore.ts` 切换
（默认跟随系统，用户手动切换后写 localStorage），`index.html` 在首帧前预置属性避免闪白。
antd 侧同步切 `darkAlgorithm`（`main.tsx` 的 `PALETTE`）。
**新增硬编码颜色前先想一下暗色**：`echarts` 这类画在 canvas 上的图表读不到 CSS 变量，需要按主题给两套色值（见 `BudgetChart` 与 `chartTheme`）。

**可观测性**：
- `TraceIdFilter` 给每个请求打 `X-Trace-Id`（上游传了就用上游的），写进日志 MDC，日志格式里已带 `[%X{traceId:-}]`；
  排查线上问题时先要 traceId，再按它捞全部日志。
- `/actuator/health` 开放探活；`/actuator/metrics` 需要登录。
- LLM 指标：`llm.calls{result=ok|failed|skipped}` 与 `llm.latency`。
  **skipped 的语义是"整条链路走了规则引擎"**（没配 Key 或显式关闭），failed 才是调用失败降级。

**本地密钥的加载顺序**：`spring.config.import: optional:file:./.env.local[.properties]`，
因此 `java -jar` / `mvn spring-boot:run` 都会自动读 `travel-agent-server/.env.local`，
不必依赖 `run-dev.ps1`。优先级：环境变量 > `.env.local` > application.yml 默认值。
**没配 `LLM_API_KEY` 时不只是"回答差一点"**：目录外城市（如海口）需要模型联网生成城市数据，
没模型时只能退回内置城市，提示文案会明确说明原因（见 `PlannerAgent.unsupportedCityMessage`）。

## 部署注意事项

1. 必须配置 `JWT_SECRET`，否则每次重启都会让全体用户掉线；
2. **生产部署设 `DEMO_ACCOUNT=false`**——否则启动时会自动创建公开弱密码账号 demo/demo123（`DataInitializer`）；
3. schema 变更走 Flyway（`db/migration/V*__`）；存量库首次启动会自动 baseline。
   `ddl-auto` 仍是 `update`，待全面审计后可切 `validate`；
4. `/h2-console/**` 在默认 profile 下是放行的，生产 profile（mysql）已关闭 H2 控制台，但建议同时从 `SecurityConfig` 的放行列表中移除；
5. `PendingPlanStore` 已落库（`pending_plan` 表，TTL 30 分钟），多实例与重启都不会丢待确认方案；
   若换成 Redis，注意保持"归属校验 + 过期清理"这两条语义；
6. 前端生产构建需自行提供 API base（当前 `baseURL` 为 `/api`，由网关或 nginx 反向代理到后端 8080）；
7. https 部署时设置 `COOKIE_SECURE=true`（登录 Cookie 加 Secure 属性）；
8. 本机 npm 10.8.2 存在 arborist bug（edgesOut 报错），安装/更新依赖请加 `--legacy-peer-deps`；
9. 路书导出需要中文字体：容器内安装 `fonts-noto-cjk`，或把任意 CJK 字体放进
   `travel-agent-server/src/main/resources/fonts/cjk.ttf`（随包分发最稳）；字体缺失时接口报业务错误而非乱码；
10. Telegram 渠道只需 `TELEGRAM_BOT_TOKEN`（长轮询，出站连接，无需公网回调）；Webhook 渠道
    务必配置强随机 `CHANNEL_WEBHOOK_SECRET`，否则端点永远 401。

## 待办清单（按优先级）

1. **P0 真实数据源联调**：高德 MCP 通道已接通（见 [guide/mcp-amap.md](guide/mcp-amap.md)），
   还差后端「Web 服务」Key（`AMAP_MAPS_API_KEY`）做真实 POI/路径/天气与 GCJ-02 坐标联调；
   酒店/餐厅开放平台同理由 `travel.mcp.servers` 扩展；
2. **P1 MySQL 8.4 实机回归（已完成 2026-11-19，验收基线 V1..V18）**：便携版 8.4.10 实例（独立 datadir / `:3307`）上，
   全新库 17 个迁移 + `validate` + 核心链路 + 「仅重启后端 / 仅重启 MySQL / 整栈重启」三类恢复全部通过；
   V19 旅行闭环迁移为后续增量，未纳入该次证据；
   证据 `.evidence/mysql84-3307-20261119-144026/`、脚本 `.e2e/acceptance/mysql-native-3307-acceptance.ps1`。
   遗留：Flyway 对 8.4 仅 WARN（建议升级）；现网库 V13 大表 DDL 影响仍建议低峰窗口验收；
3. **P1 极端旧库列类型收敛**：若某库自 `init.sql` 之后从未跑过 `ddl-auto=update`，
   `health_record.weight` 可能还是 `DECIMAL(5,1)`、`operation_history.*_state` 还是 `JSON`，
   `validate` 会报类型不符——需按 deployment 文档清单手工 `MODIFY`；
4. **P1 前端组件测试**：ChatPanel 核心交互的 jsdom 组件测试（PlanDiff 确认流程已由 e2e 覆盖；
   `BudgetExecution`、`themeStore`、`OfflineBanner` 已有单测）；
5. **P2 暗色深水区**：echarts/maplibre 读不到 CSS 变量，目前是页内手写配色，个别图表/弹窗仍硬编码；
6. **P2 Safari/iOS**：`viewport-fit`/`safe-area`/PWA 安装仅 Chromium 模拟，需真机验证；
7. **P2 会话记忆纵深**：历史已进规划 Prompt（最近 4 条、每条 120 字，仅 LLM 路径），
   但"旧方案数字"只靠 Prompt 软约束，未做结构化摘要/指代消解；
8. **P2 SSE 用例改真容器**：4 个 SSE 集成用例目前靠 `sseDispatch()` 的"等待 + 仅对 CME 重试"绕开
   MockMvc 的 `MockHttpServletResponse` 线程安全限制；彻底方案是改成 `webEnvironment=RANDOM_PORT` + 真容器；
9. **P2 二期工具**：`compare_hotel` / `check_opening_hours` / `search_ticket`。

（已完成：第十批——高德 MCP 接成一等配置（12 个工具、Windows npx 兼容、失败降级、语义适配层）；
schema 单一权威（`V1__baseline.sql` + V13 双方言 + V14(H2 配额唯一键) + 双 profile `ddl-auto=validate` + V4 语法修复与 repair 工具）；
知识库不变量（MySQL 生成列 ACTIVE 唯一、旧版本块保留窗口与每日清理、`doc_key` 消除并发首建重复）；
可观测性（`agent.stage`、`sse.disconnects`、断连取消后续阶段）；会话历史进规划 Prompt；
前端暗色模式 / 390px 移动端 / PWA 离线查看行程。
第九批——票据分用途 + 仅凭票据只读会话、`/me` 独立限流桶、配额唯一键 V8、列表分页与聚合下推 +
`pagination` 契约 + V9 索引、知识库版本化 + 回滚 API + V10、城市名匹配统一、会话记忆配置化 + 每日清理。
更早：结构化规划线上指标；`@MockBean` → `@MockitoBean`；e2e 挂 CI；结构化规划表单 + `POST /api/agent/stream`；
PWA app shell 预缓存；会话记忆、方案润色、真实天气、httpOnly Cookie、Flyway、路书 PDF、多渠道入口、
MCP 生态、LLM 结构化规划与配额、真实 Embedding、限流 Redis 计数、照片 S3、按天预算偏差、
Spring Boot 3.5.16、全栈 Docker 化。）

## 迁移脚本约定（第十批更新）

- 通用脚本放 `src/main/resources/db/migration/V*.sql`（H2 与 MySQL 都会执行）；
- **MySQL 专属语法**放 `db/migration-mysql/V*.sql`（`information_schema` 探测、`PREPARE`、生成列、多表 DELETE）；
- **H2 专属语法**放 `db/migration-h2/V*.sql`（H2 原生 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`、
  `ADD CONSTRAINT IF NOT EXISTS` 等）；现有 `V13__baseline_gap_h2.sql`、`V14__llm_quota_unique_h2.sql`；
- 两个 profile 的 `flyway.locations` 各自只加载「migration + 自己那一份方言目录」，因此**同一版本号可以
  在 `migration-mysql` 与 `migration-h2` 中各有一份**（例如 `V13`），不会冲突；
- **V15 起的追踪/任务表都在共享核心目录**：V15 `agent_run/agent_task/tool_call_trace`、V16 `content_hash/plan_execution/source_key`、
  V17 `trip.party_size`、V18 `agent_task.depends_on/attempt/replaces_task_id` 全是双方言中性 SQL，
  新增同类列时不要放进方言目录；
- **方言目录里只放该方言需要的约束**：H2 侧没有 `llm_quota_usage` 唯一键（V8 在 MySQL 目录）曾是真实缺口，
  由 `V14`(H2) 补上——新增 MySQL 专属约束时要自问「H2 是否也需要等价物」，反之亦然；
- **`V1__baseline.sql` 是「V2 之前」的等价比基线**（等价旧 `sql/init.sql`），语义上不能包含 V2+ 新增的
  列/表/索引；全新库只需要 `flyway migrate`，不再手工跑 `init.sql`（该文件已弃用）；
- **两个 profile 都是 `ddl-auto=validate`**：漏列会启动失败，不再被 `update` 掩盖；
- 已发布脚本**原则上不改**（checksum）。例外：会导致某方言**直接跑不通**的语法错误可以改，
  但必须同时提供 repair 流程（V4 的 `--（` → `-- （` 就是这个例外，见下）；
- **改过任何已发布脚本后**：本地 H2 与现网 MySQL 都要跑一次 repair，否则启动报 checksum mismatch：
  `powershell -NoProfile -ExecutionPolicy Bypass -File .tools\flyway-repair\run.ps1 -Command repair -JdbcUrl "<url>" -DbUser <u> -DbPassword <p|-> -Locations "<migration>,<方言目录>"`。
  **不要删 `flyway_schema_history`**。

## 构建与测试的本地注意事项（第十批补充）

- 本机只有 Windows PowerShell 5.1（**没有 `pwsh` 7**）。PS 5.1 把无 BOM 的 UTF-8 脚本按 ANSI 解码，
  中文注释会吞掉下一行——脚本一律纯 ASCII，或存成 UTF-8 with BOM；**PowerShell 传空密码会被吞**，
  repair 工具用 `-` 占位；
- 并行跑 maven 会争抢 `target/`：用 `.tools/mvn-lock.ps1`（命名互斥体）串行化；
- 大部分 `@SpringBootTest` 需要 `-DargLine=-Djdk.attach.allowAttachSelf=true`（Mockito 自附加）；
- 前端 `vitest`/`vite build` 需要 Node 子进程管道，在 **workspace-write 沙箱**下会 `EPERM spawn`，
  需在 unrestricted 会话里跑；
- **本机有真实 MySQL 8.0.46**：`127.0.0.1:3306`，`root/123456`（仅本地开发）。做迁移验证时**只建
  `travel_agent_b10_*` 这类临时库、用完 DROP**，不要碰 `lab3_*`/`lab4_mall`/`sky_take_out` 等库；
- e2e 本地跑法：先起后端（`RATE_LIMIT_ENABLED=false` 便于连跑）+ `cd travel-agent-web && npx playwright test`
  （配置里的 `webServer` 会自己起 preview 4173）。

## 已完成的加固（2026-10 批次）

1. **SSE / 照片凭证**：登录 JWT 不再出现在 URL，改为短期只读票据（`AccessTicketService` + `api/ticket.ts`）；
   第九批再收窄为「分用途 + 仅凭票据=只读会话 + `Referrer-Policy: no-referrer`」；
2. **接口限流**：`RateLimitFilter`（登录注册按 IP、Agent 与上传按用户），超限 429 + Retry-After；
   第九批补 `/api/auth/me` 独立会话桶、`Retry-After ≥ 1`、Redis fail-open 可观测；
3. **照片安全**：MIME 白名单 + 文件头嗅探 + 服务端推导响应类型，堵住 SVG 存储型 XSS；
4. **照片运维**：张数与容量配额、先写库后落盘（消除孤儿文件）、HEIC 前端可读提示；
5. **列表边界**：`Paging` 统一 limit/offset（默认 500、上限 1000），覆盖全部列表接口；
   第九批把 5 个高频列表改成 DB 层 `Pageable`（默认 20、上限 200）并新增 `pagination` 元数据；
6. **数据库索引**：trip / trip_photo / itinerary_item / expense / operation_history / schedule_item 等高频查询字段；
   第九批新增 `V9` 的 7 条复合索引（等值列在前、排序列在后）；
7. **线程池**：Agent、联网城市生成、足迹定位统一走 `agentTaskExecutor`，不再占用 ForkJoin commonPool；
8. **单机状态**：`PendingPlanStore` 从进程内 Map 改为 `pending_plan` 表，确认流程可跨实例、可重启。
