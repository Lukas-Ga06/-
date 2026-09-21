# 开发与配置指南

## 环境变量

仓库内不保存任何密钥，以下变量一律通过环境变量注入，未配置时的降级行为一并说明。

| 变量 | 是否必需 | 未配置时的行为 |
| --- | --- | --- |
| `JWT_SECRET` | 生产必需 | 启动时生成一次性随机密钥并打印 WARN：本地开箱即用，但**服务重启后所有已签发 token 失效**；生产不配置则每次发布都会强制全体用户重新登录 |
| `LLM_API_KEY` | 可选 | Agent 全部走规则引擎：意图识别、方案生成、文案润色使用内置模板与规则，功能完整但效果弱于模型 |
| `AMAP_KEY` | 可选 | 城市 POI 走「大模型生成坐标 + OSM Nominatim」兜底路径，速度较慢 |
| `APP_TIMEZONE` | 可选（已设默认） | 默认 `Asia/Shanghai`；若部署在其他时区需显式覆盖，否则「今天/明天」会按上海时间计算 |

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

**工具层只读**：`AgentTool.readOnly()` 默认为 true，`ToolRegistry` 会拒绝注册写操作工具；
任何"会改数据"的能力都必须实现为 `AgentPlan` 的操作项，经确认后由 `ExecutorAgent.commit` 落地。
HIGH 风险只表示"方案需要显式确认"（超预算），不是"抛异常报错"。

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

**意图路由**：添加日程要求「排期名词 + 明确时间点（或排期动词）」同时成立，
不能只靠关键词（`parseScheduleRequest` 对任何非空文本都返回非 null）。
兜底意图是 `CHITCHAT`（返回能力提示），不要贸然返回 `PLAN_TRIP`，否则"没听懂"会变成"暂不支持该目的地"。

**时区**：`TimeZoneConfig` 在启动时把 JVM 默认时区固定为 `travel.timezone`（默认 `Asia/Shanghai`）。
`LocalDate.now()` 依赖它，新增时间相关逻辑时不要绕开。

**越权保护**：所有行程相关接口先做 `tripService.getOwned(tripId, userId)`；
批量操作（`applyOperations`）还会校验每个条目确实属于该行程。

**校验**：DTO 使用 `jakarta.validation` 注解，控制器参数加 `@Valid`；
「格式正确但值非法」的日期（如 2026-02-30）通过 `util/Dates` 统一转 400。

**前端分包**：路由全部懒加载（`React.lazy`），重依赖（echarts / maplibre / leaflet / antd）在 `vite.config.ts` 中显式分 chunk。
页面上新增重型组件时请用 `lazy` + `Suspense`，避免回到单包 3MB。

**地图组件**：`.fp-map` / `.maplibregl-map` 是 maplibre 的容器，maplibre 会给它加 `position: relative`，
其样式在懒加载后才注入且优先级不低——给容器设尺寸时不要用 `absolute + inset`，**必须显式写 height**，否则容器塌成 0 高、地图完全不渲染（已踩过一次）。
足迹地图的球体投影依赖 maplibre v5+，代码里做了运行时探测，旧版本自动退回平面 3D。

## 部署注意事项

1. 必须配置 `JWT_SECRET`，否则每次重启都会让全体用户掉线；
2. 将 `spring.jpa.hibernate.ddl-auto` 从 `update` 改为 `validate` 并引入 Flyway/Liquibase 管理 schema；
3. `/h2-console/**` 在默认 profile 下是放行的，生产 profile（mysql）已关闭 H2 控制台，但建议同时从 `SecurityConfig` 的放行列表中移除；
4. 多实例部署前需把 `PendingPlanStore` 从内存迁移到 Redis，否则用户确认时会打到没有该方案的实例上；
5. 前端生产构建需自行提供 API base（当前 `baseURL` 为 `/api`，由网关或 nginx 反向代理到后端 8080）。

## 待办清单（按优先级）

1. SSE 的 token 走 query 参数（受 EventSource 限制），会进入访问日志；改为一次性短时 ticket；
2. 接口限流与每用户 LLM 配额（注册开放 + 单次对话可触发多轮模型调用）；
3. `npm audit` 中的依赖漏洞：maplibre-gl(critical)、vite/esbuild(high)、echarts、react-router(moderate)；
4. 会话记忆：`sessionId` 每次请求重建，多轮指代只能靠 `selectedElement`；需要会话级上下文存储；
5. 预算对比：目前只有"计划成本"与"实际支出"两套数，缺"计划 vs 实际"偏差视图；
6. 知识库运营：`RagBootstrap` "有文档就跳过"，更新 `knowledge.json` 需手工清库（缺版本/指纹机制）；
7. 在线城市缓存用 `contains` 做模糊匹配，可能串城市（如"洛阳"命中"阳"）；
8. 前端待确认方案刷新即丢（后端 TTL 30 分钟，缺按 planId 取回的接口）；
9. 列表接口分页（trips / expenses 当前全量返回）；
10. DB 索引：`trip_id`、`user_id`、`(user_id, date)` 等高频查询字段；
11. 可观测性：Actuator、指标、traceId、LLM 失败率；
12. RAG 从「字符 bigram 哈希」升级为真实 Embedding；
13. 前端体验：移动端布局（窄视口下侧栏会占掉大部分宽度）、暗色模式、PWA 离线查看行程。
