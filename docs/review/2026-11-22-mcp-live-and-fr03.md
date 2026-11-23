# 高德 MCP 12 工具逐项核对 + FR-03 真模型复跑（2026-11-22）

> 快照日期：2026-11-22 ｜ 验证范围：`mcp-amap` §5.2 两处未验事项（12 个 MCP 工具的业务输出、真实 Key 下的 MCP 链路）
> 与 `v2.2-iteration` FR-03（Agent 自主工具调用循环）的真模型复跑 ｜ 环境：Windows / JDK 17 /
> `spring-boot:run`（端口 8081、独立 H2 文件库）/ 高德「Web服务」Key / `deepseek-flash`
> ｜ 结论：**12 个工具全部真实返回可用业务数据（其中 2 个工具的上游参数缺陷已定位到源码）；
> FR-03 真模型链路打通，研究阶段自主调用 4 次工具，但暴露 3 个产品级缺口**

## 1. 为什么现在能验了

两件此前被记成「未验」的事，卡的是同一个东西：余额与 Key。

| 事项 | 此前记录 | 现在的事实 |
| --- | --- | --- |
| FR-03 真模型 | `docs/review/2026-11-19-acceptance.md` §4.2：DeepSeek 余额 0 → `402 Insufficient Balance`；`2026-11-22-real-model-acceptance.md` §5 仍是「未验」 | 余额 **¥0.76**，`is_available=true`。模型列表只有 `deepseek-flash` / `deepseek-v4-pro`（默认 `deepseek-chat` 不存在） |
| MCP 12 工具业务输出 | `docs/guide/mcp-amap.md` §5.2：「只验了能被注册与调用到，业务输出没有逐一核对」 | 已用真实 Key 逐个调用并逐项对账，见 §3 |

> 注：`travel-agent-server/.env.local` 里那句「当前这枚是前端 JS Key，后端用它会被高德拒绝」**已过期**。
> 现在生效的 `AMAP_KEY`（前缀 `5f74d350`）是「Web服务」平台 Key：`/v3/weather/weatherInfo` 直连即
> `status=1 infocode=10000`。报告里之所以还能看到这句，是文件里前一行注释没删。

## 2. 怎么跑（可重复）

```powershell
# MCP 12 工具核对（不进 CI，缺 Key 时整类跳过）
$env:MCP_IT = "true"; $env:MCP_ENABLED = "true"; $env:AMAP_MAPS_API_KEY = "<Web服务 Key>"
cd travel-agent-server; mvn -B -Dtest=McpAmapLiveTest test

# FR-03 真模型复跑
$env:LLM_MODEL = "deepseek-flash"; $env:MCP_ENABLED = "true"
$env:AGENT_BUDGET_TOTAL_MS = "120000"   # 默认 30s 会把研究+规划挤成降级，验收期给足
$env:RATE_LIMIT_ENABLED = "false"
mvn -B spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

核对工具是新增的 `McpAmapLiveTest`（`@EnabledIfEnvironmentVariable(MCP_IT=true)`，与
`MySqlFlywayMigrationTest` 同一套「人工触发、不进 CI」惯例）：2 个用例、实测 22.6s、12 次真实调用。

## 3. 12 个工具逐项对账

远端 `mcp-server/amap-maps` `0.1.0`（npm 包 `@amap/amap-maps-mcp-server@0.0.8`），`tools/list` 12 个，
注册进 `ToolRegistry` 后全部 `readOnly=true`、前缀 `mcp_amap_`。

| 远端工具 | 实调用例 | 真实输出（节选） | 业务结论 |
| --- | --- | --- | --- |
| `maps_geo` | 天安门 + city=北京 | 北京市东城区 `116.397463,39.909187`，`level=兴趣点` | ✅ 结果对；⚠️ **`city` 参数被上游丢弃**（见 §4.1） |
| `maps_regeocode` | `116.397428,39.90923` | 北京市 / 东城区 | ✅ 对；输出字段名是 `provice`（上游拼写错误），直辖市 `city` 返回 `[]` |
| `maps_direction_driving` | 西湖 → 杭州东站 | 7713 m / 1281 s / 10 steps | ✅ 距离量级正确；`path` 字段为空（该包只透传 steps，不含 polyline） |
| `maps_direction_walking` | 西湖 → 雷峰塔方向 | 6203 m / 4962 s / 20 steps | ✅ |
| `maps_direction_transit_integrated` | 杭州东站 → 西湖（city/cityd=330100） | 7388 m / 4 个换乘方案 / 首方案「地铁1号线(萧山国际机场--湘湖)」/ 步行 1264 m | ✅ 换乘结构完整（segments + buslines + via_stops） |
| `maps_bicycling` | 西湖 → 灵隐寺方向 | `data.paths[0]` 7781 m / 1867 s | ✅（v4 接口，按 `errcode` 判错） |
| `maps_distance` | 北京天安门 → 上海人民广场 | `type=0`（直线）**1069389 m**；`type=1`（驾车）**1212467 m** | ✅ 两个口径自洽：直线 1069 km < 驾车 1212 km，都符合真实地理 |
| `maps_text_search` | 西湖 + city=杭州 | 20 条 POI，首条「杭州西湖风景名胜区」`typecode=110202` | ✅ 结果对；⚠️ **`types` 参数被上游丢弃**（见 §4.2） |
| `maps_around_search` | 西湖 1 km + 餐厅 | 20 条 POI，首条「杭州JW万豪酒店亚洲风尚餐厅」`050217` | ✅ |
| `maps_search_detail` | 上一步检索得到的真实 id | 西湖风景名胜区详情（含 `opentime2`、`biz_ext` 平铺） | ✅「检索 → 详情」闭环成立 |
| `maps_weather` | adcode `330100` | 杭州市 4 天预报（11-22 晴/多云 17/9℃ …） | ✅ 对；口径是**预报**（服务端恒传 `extensions=all`），不是实况 |
| `maps_ip_location` | `114.114.114.114` | `{province: [], city: [], adcode: [], rectangle: []}` | ⚠️ 无结果时返回**空数组**而非错误，调用方必须自己判空（见 §4.3） |

应用侧同样确认：启动日志 `MCP 服务器 [amap] (mcp-server/amap-maps) 已接入，注册 12 个工具`，
12 个工具全部注册成功（`allTwelveToolsAreRegistered` 断言名字集合与设置）。

## 4. 核对暴露的上游缺陷（已定位到 `build/index.js`）

### 4.1 `maps_geo` 的 `city` 是死参数

```js
// 声明：inputSchema.properties 里有 city
// 分发：const { address, city } = request.params.arguments;  → handleGeo(address, city)
async function handleGeo(address, city, sig) {
    url.searchParams.append("key", ...); url.searchParams.append("address", address);
    url.searchParams.append("source", "ts_mcp");       // ← city 全程没写进 URL
}
```

现场证据：`maps_geo(address=东方明珠, city=上海)` → **湖南省邵阳市大祥区 `111.469825,27.230055`**（`level=住宅区`），
与「不传 city」直接打 REST 的返回完全一致。对旅行的实际含义：**跨城同名地点会被解析到别的城市**，
而且 `isError=false`，模型会当成真事实用。

### 4.2 `maps_text_search` 的 `types` 是死参数

```js
case "maps_text_search": {
    const { keywords, city, citylimit } = request.params.arguments;   // ← 没有 types
    return await handleTextSearch(keywords, city, citylimit);
}
```

schema 里声明了 `types`（「POI类型，比如加油站」），handler 拿到就扔；反过来 `citylimit` 能透传但
**schema 里没声明**，模型根本不知道该传。

> 本条直接影响本仓库代码：`AmapDataService.textSearch` 现在会传
> `args("keywords", ..., "city", ..., "types", types)`——这一路走 MCP 时 `types` 被静默丢弃，
> 只有退回 REST 分支才会真正生效。当前 `textSearch` 无生产调用方，属于**接线前必须处理**的债。

### 4.3 空数组语义与其他形状差异

- `maps_ip_location` 查不到时返回 `{"province": [], "city": [], ...}`，`isError=false`：不是错误也不是结果，
  是「没查到」。消费方必须判空，否则会把 `[]` 当成城市名去拼文案。
- `maps_regeocode` 的字段名是 `provice`（上游拼写），直辖市的 `city` 是 `[]`。
- `maps_geo`/`maps_text_search` 的 `photos`、`cost`、`street`、`number` 等字段经常是 `[]`（不是 null，也不是字符串）。

## 5. FR-03 真模型复跑

两次请求、同一个后端实例、`llmVerified=true`、`llmModel=deepseek-flash`。

### 5.1 请求 A：真实模型自主选用 MCP 工具（INVOKE_TOOL）

```
"帮我规划杭州周末两日游，两个人，预算3000，先查一下杭州这两天的天气，再安排西湖附近的景点和吃饭"
→ intent=INVOKE_TOOL, status=COMPLETED, 6.4s
→ 回复正文直接是 mcp_amap_maps_weather 的真实返回值（杭州市 4 天预报）
```

运行追踪：`INTENT intent-agent 6085ms → TOOL tool-invoker 168ms`，
`toolCalls: mcp_amap_maps_weather success=True 163ms args={"city":"杭州"}`。

**这条是本次最硬的证据**：模型在 12 个 MCP 工具 + 6 个内置工具的目录里自己挑了 MCP 天气工具、
自己构造了 `{"city":"杭州"}`，应用侧真实执行、真实拿到高德数据并原样回给用户。
此前 `mcp-amap` §5.2 记的「真 Key 下的 MCP 链路还没跑过」，到此为止。

### 5.2 请求 B：FR-03 研究循环（PLAN_TRIP）

```
"帮我规划一个杭州两日游，两个人，预算3000"
→ intent=PLAN_TRIP, status=NEED_CONFIRMATION, 24.1s
```

运行追踪的真实任务链：

| 任务 | 时长 | 结论 |
| --- | --- | --- |
| `INTENT` | 447 ms | PLAN_TRIP |
| **`RESEARCH`** | **3183 ms** | **4 步工具调用后「步骤预算耗尽」，未给 FINAL** |
| `PLAN` | 22031 ms | 生成 11 项操作（4 景点 + 4 餐饮 + 酒店 + 交通） |
| `REVIEW` | 7 ms | 3 项高风险（时间冲突） |
| `REPLAN` | 3 ms | 逐条后移：Day1 +17min、Day2 三处 +2/+13/+9min |
| `REVIEW` | 2 ms | 复审通过 |
| `PREVIEW` | 0 ms | 重建 Diff |

研究阶段实际发生的 4 次调用（`tool_call_trace`，全部挂在 RESEARCH 任务下）：

| 次序 | 工具 | 参数 | 结果 |
| --- | --- | --- | --- |
| 1 | `query_weather` | `{"city":"杭州","date":"2026-11-23"}` | ✅ |
| 2 | `query_weather` | `{"city":"杭州","date":"2026-11-24"}` | ✅ |
| 3 | `search_attraction` | `{"location":"杭州西湖","indoor":false}` | ❌ 参数名不存在 |
| 4 | `search_attraction` | `{"location":"杭州","indoor":false}` | ❌ 同上 |

**这正是 FR-03 要证明的东西**：模型自己决定「先查两天天气 → 再找景点」，
观察进下一次决策，收集到的天气事实进入结构化规划 Prompt（`StructuredPlanner.researchBlock`），
最终方案的时间冲突由审核驱动重规划修掉。没有一次「降级为规则模板」（日志实测 0 次）。

## 6. 复跑暴露的 4 个缺口（按可修优先级）

### 6.1 内置工具在循环提示词里没有参数名 → 模型必须猜（可直接修）

`AgentToolLoop.decisionMessages` 给模型看的工具清单只有「名字 + 一句话描述」；
MCP 工具却有桥接层拼的参数摘要（`(参数: address*(待解析地址), city)`）。
于是模型对 `search_attraction` 猜了 `location` / `indoor` 两个**都不存在**的参数，
白烧了 4 步里的 2 步（`SearchAttractionTool` 只认 `city` / `venue` / `nearLat` / `nearLng` / `exclude`）。

修法：把内置只读工具的参数名一并渲染进决策提示（至少对 `RESEARCH_TOOLS` 白名单），
与 MCP 工具口径统一。改动小、收益直接。

### 6.2 研究循环系统性用满步数、从不输出 FINAL（需要产品决策）

`RESEARCH_MAX_STEPS=4`，模型连续 4 次 `CALL_TOOL`，第 5 步已无预算，于是「研究未完成」。
已收集的观察仍然被使用（这一点设计是对的），但摘要「研究未完成（步骤预算耗尽）」会一路写进运行追踪，
运维看到的是「研究失败」而实际是「研究够用了但没说停」。

可选修法：① 决策提示里明确「信息足够就先输出 FINAL」；② 步数预算提到 6；
③ 抓到「连续两次同类调用」时主动收尾。三条都需要一次真实调用验证，建议与 6.1 一起做。

### 6.3 MCP 路线分支没有 10021 限流重试（REST 分支有）

本次 7 段路线里，MCP 分支连续撞 `CUQPS_HAS_EXCEEDED_THE_LIMIT`（infocode 10021）→ 打 ERROR 日志 →
退回 REST → REST 分支自带「一次退避重试」所以最终都成功（`routeProvider=amap`、`amapVerified=true`）。

问题是：突发算路时 MCP 分支会先烧一遍失败的配额再退回，日志里 3 条 ERROR 会误导排障。
修法：把 REST 分支那套「10021 → 220~400ms 退避重试一次」同样套在 MCP 分支上（或失败后短期熔断）。

### 6.4 `/api/system/status` 不暴露 MCP 状态

状态页能自报 `llmVerified` / `routeProvider` / `amapVerified`，但没有「MCP 接入了几个工具」。
运维判断 MCP 是否生效只能翻启动日志。修法：status 里加一个 `mcpTools` 计数（失败时为 0）。

## 7. 成本与边界

- 两次真实请求合计 **≈ ¥0.03**（余额 0.76 → 0.73）。单次代价参考：一次 `INVOKE_TOOL` 意图 + 一次工具调用
  不到 ¥0.005；一次完整的「研究 4 步 + 结构化规划 + 审核 + 重规划」约 ¥0.025。**余额 ¥0.73 大约还能跑
  25–30 次完整规划**，批量回归前先充值。
- 研究阶段 4 次工具调用里有 2 次是本地目录工具（微秒级），不产生外部调用；
  真正的外部调用是 `query_weather` 之外的路线计算（7 次 REST + 3 次 MCP 失败）。
- 本次没有验证：MCP 工具的并发调用、`maps_geo` 的跨城歧义在真实规划里的后果（因为
  MCP 尚未接进规划候选清单）、`deepseek-v4-pro` 下的表现、长上下文与超时边界。
- 环境侧结论：`AGENT_BUDGET_TOTAL_MS` 必须调大（本次 120s）才能跑完整研究+规划；
  默认 30s 在这个模型上会走降级路径——这不是缺陷，是部署参数的选择。

## 8. 证据

| 路径 | 内容 |
| --- | --- |
| `.evidence/mcp-live-probe/raw.json` | 12 个工具的**原始返回文本**（含 `maps_ip_location` 空数组） |
| `.evidence/mcp-live-probe/extra.json` | `type=0`/`type=1` 测距对照、`maps_geo` 跨城错配现场 |
| `.evidence/mcp-live/mvn-run.log` | `McpAmapLiveTest` 完整运行日志（含 12 工具输出摘要） |
| `.evidence/fr03-real/backend.log` | 后端启动（注册 12 个 MCP 工具）+ 请求日志 + MCP 路线 10021 告警 |
| `.evidence/fr03-real/system-status-after.json` | `llmVerified=true` / `routeProvider=amap` / `amapVerified=true` |
| `.evidence/fr03-real/chat-response.json` | INVOKE_TOOL 完整响应（含真实天气 JSON） |
| `.evidence/fr03-real/plan-response.json` | PLAN_TRIP 完整响应（方案正文 + warnings） |
| `.evidence/fr03-real/run-detail-invoke.json` / `run-detail-plan.json` | 两次请求的任务链与 11 条工具调用 |

> 上面这些都在 gitignore 的 `.evidence/` 里（含原始 Key 调用痕迹与本地库），**不入库**；
> 入库的是本报告与新增的 `McpAmapLiveTest`。Key 全程只经环境变量注入，未写入任何文件。