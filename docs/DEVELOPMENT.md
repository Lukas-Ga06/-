# 开发与配置指南

## 环境变量

仓库内不保存任何密钥，以下变量一律通过环境变量注入，未配置时的降级行为一并说明。

| 变量 | 是否必需 | 未配置时的行为 |
| --- | --- | --- |
| `JWT_SECRET` | 生产必需 | 启动时生成一次性随机密钥并打印 WARN：本地开箱即用，但**服务重启后所有已签发 token 失效**；生产不配置则每次发布都会强制全体用户重新登录 |
| `LLM_API_KEY` | 可选 | Agent 全部走规则引擎：意图识别、方案生成、文案润色使用内置模板与规则，功能完整但效果弱于模型 |
| `AMAP_KEY` | 可选 | 城市 POI 走「大模型生成坐标 + OSM Nominatim」兜底路径，速度较慢 |
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

当前规模：后端 49 个用例（认证/越权、Agent 计划与撤销、工具层契约、RAG 过滤、日期映射、意图路由、
冲突检查、记账对账、时区），前端 28 个用例（agent store、SSE 客户端、axios 401 分流、足迹数据）。

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

**发票记账**：`POST /api/office/invoice/{id}/expense` 把发票写成行程支出，并把 `expense_id` 写回发票；
同一张发票重复记账返回 409。办公类发票归入「其他」分类。

**浏览器通知**：只在「已授权 + 页面 hidden + 本会话未通知过」时发（`lib/reminderNotify.ts`），
权限必须由用户点击按钮触发 `Notification.requestPermission()`。

**发票文件**：`InvoiceTextExtractor` 负责文件 → 文本：PDF 用 PDFBox 抽文字（扫描件会 422），
图片调用 `TESSERACT_CMD` 指向的 Tesseract（`-l chi_sim+eng --psm 6`）；配置了
`TESSDATA_PREFIX` 时追加 `--tessdata-dir`，用于语言包装在用户目录（Program Files 无写权限）的场景。
未配置 Tesseract 时 422 并提示；
原始文件存 `travel.invoices.dir`，库里记录 `source_file/source_type`。
OCR 文本直接进入 `extractInvoice`，优先由模型结构化，失败再走规则兜底；
兜底日期同时支持 `2026-10-10`、`2026/10/10`、`2026年10月10日`。

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

**天气数据源**：`WeatherService.forecast` 是唯一入口。`WEATHER_REAL=true` 时先走 Open-Meteo
（内置城市用目录坐标均值，目录外走其地理编码），失败或超出预报窗口（过去 / 16 天外）降级模拟。
模拟路径的 `mentionRain` 演示行为仅存在于降级路径。新增天气相关功能不要绕过该入口。

**Flyway**：`db/migration/V*__*.sql` 是 schema 变更的唯一通道（存量库已 baseline 在版本 1）。
`CREATE INDEX IF NOT EXISTS` 在 MySQL 不可用，索引交给实体 `@Index` + ddl-auto 补齐；
脚本要同时兼容 H2（MODE=MySQL）与 MySQL 8。`ddl-auto` 暂留 `update`，全面审计后才切 `validate`。

**校验**：DTO 使用 `jakarta.validation` 注解，控制器参数加 `@Valid`；
「格式正确但值非法」的日期（如 2026-02-30）通过 `util/Dates` 统一转 400。

**前端分包**：路由全部懒加载（`React.lazy`），重依赖（echarts / maplibre / leaflet / antd）在 `vite.config.ts` 中显式分 chunk。
页面上新增重型组件时请用 `lazy` + `Suspense`，避免回到单包 3MB。

**地图组件**：`.fp-map` / `.maplibregl-map` 是 maplibre 的容器，maplibre 会给它加 `position: relative`，
其样式在懒加载后才注入且优先级不低——给容器设尺寸时不要用 `absolute + inset`，**必须显式写 height**，否则容器塌成 0 高、地图完全不渲染（已踩过一次）。
足迹地图的球体投影依赖 maplibre v5+，代码里做了运行时探测，旧版本自动退回平面 3D。

## 设计系统（方向 B：内容优先）

**令牌**：`travel-agent-web/src/styles/global.css` 顶部的 `:root` 是唯一来源。
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
**新增硬编码颜色前先想一下暗色**：`echarts` 这类画在 canvas 上的图表读不到 CSS 变量，需要按主题给两套色值（见健康页）。

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
2. schema 变更走 Flyway（`db/migration/V*__`）；存量库首次启动会自动 baseline。
   `ddl-auto` 仍是 `update`，待全面审计后可切 `validate`；
3. `/h2-console/**` 在默认 profile 下是放行的，生产 profile（mysql）已关闭 H2 控制台，但建议同时从 `SecurityConfig` 的放行列表中移除；
4. `PendingPlanStore` 已落库（`pending_plan` 表，TTL 30 分钟），多实例与重启都不会丢待确认方案；
   若换成 Redis，注意保持"归属校验 + 过期清理"这两条语义；
5. 前端生产构建需自行提供 API base（当前 `baseURL` 为 `/api`，由网关或 nginx 反向代理到后端 8080）；
6. https 部署时设置 `COOKIE_SECURE=true`（登录 Cookie 加 Secure 属性）；
7. 本机 npm 10.8.2 存在 arborist bug（edgesOut 报错），安装/更新依赖请加 `--legacy-peer-deps`；
8. 路书导出需要中文字体：容器内安装 `fonts-noto-cjk`，或把任意 CJK 字体放进
   `travel-agent-server/src/main/resources/fonts/cjk.ttf`（随包分发最稳）；字体缺失时接口报业务错误而非乱码；
9. Telegram 渠道只需 `TELEGRAM_BOT_TOKEN`（长轮询，出站连接，无需公网回调）；Webhook 渠道
   务必配置强随机 `CHANNEL_WEBHOOK_SECRET`，否则端点永远 401。

## 待办清单（按优先级）

1. 每用户 LLM 配额：限流只按请求数计，还不能按天限制模型开销；
2. 预算对比：目前只有"计划成本"与"实际支出"两套数，缺"计划 vs 实际"偏差视图；
3. 知识库运营：`RagBootstrap` "有文档就跳过"，更新 `knowledge.json` 需手工清库（缺版本/指纹机制）；
4. RAG 从「字符 bigram 哈希」升级为真实 Embedding；
5. 前端：PWA 离线查看行程（移动端布局与暗色主题已完成）；
6. LLM 结构化规划：模型输出 JSON 操作列表、规则引擎校验兜底，替代「模板拼装 + 文案润色」；
7. Spring Boot 3.3 → 3.5；多实例收尾（限流 Redis 化、照片对象存储）；全栈 Docker 化（注意路书字体，见部署注意事项 8）；
8. 渠道深化：微信公众号新消息回调适配器（XML 协议，需公网回调地址）；路书导出工具化（generate_trip_summary）。

（已完成 2026-10-24，见 CHANGELOG：会话记忆、方案文案润色、路由级错误边界、真实天气
（Open-Meteo）、httpOnly Cookie 登录态、Flyway baseline + 增量脚本、npm audit 清零
（maplibre 6 / vite 6 / react-router 7 / echarts 6 / happy-dom 21）、路书 PDF 导出、
多渠道入口（Telegram / 通用 Webhook）、MCP 外部工具生态（HTTP + stdio）。）

## 已完成的加固（2026-10 批次）

1. **SSE / 照片凭证**：登录 JWT 不再出现在 URL，改为短期只读票据（`AccessTicketService` + `api/ticket.ts`）；
2. **接口限流**：`RateLimitFilter`（登录注册按 IP、Agent 与上传按用户），超限 429 + Retry-After；
3. **照片安全**：MIME 白名单 + 文件头嗅探 + 服务端推导响应类型，堵住 SVG 存储型 XSS；
4. **照片运维**：张数与容量配额、先写库后落盘（消除孤儿文件）、HEIC 前端可读提示；
5. **列表边界**：`Paging` 统一 limit/offset（默认 500、上限 1000），覆盖全部列表接口；
6. **数据库索引**：trip / trip_photo / itinerary_item / expense / operation_history / health_record 等高频查询字段；
7. **线程池**：Agent、联网城市生成、足迹定位统一走 `agentTaskExecutor`，不再占用 ForkJoin commonPool；
8. **单机状态**：`PendingPlanStore` 从进程内 Map 改为 `pending_plan` 表，确认流程可跨实例、可重启。
