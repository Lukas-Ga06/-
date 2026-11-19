# 高德 MCP 接入（真实地图数据源）

> 面向**要给 Agent 接真实地图数据**的后端开发者，以及**要排「为什么模型没查地图」**的运维。
> 事实来源两类，缺一不可：① 真实包 `@amap/amap-maps-mcp-server@0.0.8` 的静态源码与本机实测
> （`npx` 真起子进程、真握手、真 `tools/list`）；② 本仓库的生产代码（`mcp/` 包与 `IntentAgent`）。
> 文中每个结论都在第 5 节标注了「已验证 / 未验证」，原始探针记录与脚本见 `.tools/amap-mcp-probe/`
> （`FINDINGS.md`、`RealAmapBridgeProbe.java`、`probe-*.js`；该目录不参与 mvn / npm 构建）。

高德 MCP 是本项目接入的**第一个真实数据源**。它不改动任何既有链路：不开启时行为与第九批完全一致；
开启后只是往 `ToolRegistry` 里多注册 12 个只读工具，模型经 `INVOKE_TOOL` 意图调用。

---

## 1. 它是什么

| 项 | 值 | 依据 |
| --- | --- | --- |
| 包 | `@amap/amap-maps-mcp-server@0.0.8` | `npm view`（2026-11-16） |
| `bin` | `mcp-amap` → `build/index.js` | 包内 `package.json` |
| 启动方式 | `npx -y @amap/amap-maps-mcp-server` | 包 README 的 `mcpServers` 写法 |
| 协议 | MCP over **stdio**（行分隔 JSON-RPC；协议版本 `2024-11-05`） | 实测 `initialize` |
| 唯一必需环境变量 | **`AMAP_MAPS_API_KEY`** | `build/index.js` 的 `getApiKey()` |
| 工具数 | **12** 个 `maps_*` | 实测 `tools/list` |
| 服务端 `serverInfo` | `{"name":"mcp-server/amap-maps","version":"0.1.0"}` | 实测 `initialize` |

### 1.1 两个 Key 不要混用

这是接入时**最容易踩的坑**：项目里同时存在三个高德 Key（两个后端 + 一个前端），变量名不同、用途不同、类型也不同。

| 变量 | 用途 | 谁在消费 | 缺省时 |
| --- | --- | --- | --- |
| `AMAP_KEY` | 后端 REST 调高德 **POI / 路径**（`travel.amap.key`），如 `AmapDataService` | `AmapDataService`，走 HTTP | 无真实数据源，走目录/估算兜底 |
| `AMAP_MAPS_API_KEY` | **MCP 子进程**的 Key（`travel.mcp.servers[].env`） | 高德 MCP server 自己（stdio 子进程） | 子进程启动即失败，MCP 段整体关闭（见 4.1） |
| `VITE_AMAP_KEY` | 前端「Web 端(JS API)」Key，画行程地图 | `travel-agent-web` 的 `amapLoader` | 行程地图不渲染 |

注意是单数 **`KEY`**（`AMAP_MAPS_API_KEY`），不是 `KEYS`。两个后端变量都取「Web 服务」类型的 Key，但**分开注入**：
一处改动只影响一条链路，不会因为「前端要换 Key」把后端定位或 MCP 一起带崩。

#### 1.1.1 坐标：高德给 GCJ-02，本仓对外统一 WGS-84

高德 REST / MCP 返回的 `location` 都是 **GCJ-02**。`AmapDataService` 与 `OnlineCityService` 在解析
`location` 时统一反算成 **WGS-84** 再落库/返回；前端 `AMapProvider` 与足迹高德底图渲染前再转回 GCJ-02。
两端公式分别在 `travel-agent-server/.../util/CoordinateTransform.java` 与
`travel-agent-web/src/lib/coords.ts`，修改时必须同步，否则地图会整体偏移几百米。

### 1.2 Key 在模块加载期校验（决定了排障顺序）

包内代码在**模块加载期**就调 `getApiKey()`，不是等到 `tools/call`：

```js
function getApiKey() {
    const apiKey = process.env.AMAP_MAPS_API_KEY;
    if (!apiKey) {
        console.error("AMAP_MAPS_API_KEY environment variable is not set");
        process.exit(1);
    }
    return apiKey;
}
const AMAP_MAPS_API_KEY = getApiKey();   // 模块加载期校验
```

后果有两条，直接影响你怎么看日志：

1. **没 Key 连 `initialize` 都到不了**——子进程 `exit(1)`，stdout 没有任何响应，本项目侧表现为握手超时；
2. **Key 无效不影响握手**——`initialize` 与 `tools/list` 都不访问网络，只有真正 `tools/call` 才返回业务错误。
   所以「日志里 12 个工具都注册成功了」**不能**证明 Key 可用。

---

## 2. 怎么开（三步）

### 2.1 申请 Key

高德开放平台申请「Web 服务」类型 Key，**只放环境变量，不写进仓库**。

### 2.2 打开 MCP 并配上高德 server

`travel.mcp.enabled` 默认 **false**（关闭时不拉起任何子进程）。应用配置片段（已写在
`travel-agent-server/src/main/resources/application.yml` 的 `travel.mcp`）：

```yaml
travel:
  mcp:
    enabled: ${MCP_ENABLED:false}
    servers:
      - name: amap
        command: ["npx", "-y", "@amap/amap-maps-mcp-server"]
        env:
          AMAP_MAPS_API_KEY: ${AMAP_MAPS_API_KEY:}
        read-only: true
        timeout-ms: 30000
```

| 字段 | 作用 | 说明 |
| --- | --- | --- |
| `name` | 命名空间 | 注册后的工具名为 `mcp_amap_<工具名>`；`name` 重名会让后者整批被拒 |
| `command` | stdio 启动命令 | **数组**形式，命令与参数分开写，避免 `npx -y ...` 被错误拆分 |
| `env` | 子进程环境变量 | 用 `${AMAP_MAPS_API_KEY:}` 占位；此前无法给子进程传 Key，本批补上 |
| `read-only` | 是否只读注册 | 默认 `true`；`ToolRegistry` 会拒绝非只读工具 |
| `timeout-ms` | 单次 JSON-RPC 等待（含握手） | 默认 20000；**建议 30000**，`npx` 首次冷启动要下载包 |

`McpProperties` 的所有新字段都有默认值，既有的 `travel.mcp.servers` 配置不写 `env` / `timeout-ms` 也能照常启动。

### 2.3 设环境变量并启动

```bash
# 本机（PowerShell）
$env:MCP_ENABLED = "true"
$env:AMAP_MAPS_API_KEY = "<你的 Web 服务 Key>"
cd travel-agent-server; mvn spring-boot:run
```

看到这一行即接入成功：

```
MCP 服务器 [amap] (mcp-server/amap-maps) 已接入，注册 12 个工具
```

> **Windows 不用手写 `cmd /c`**：`StdioMcpTransport.resolveCommand` 会自动给 `npx` / `npm` / `pnpm` / `yarn`
> 以及 `*.cmd` / `*.bat` 补前缀。不补的话 `ProcessBuilder` 直接 `CreateProcess error=2`（`npx` 实际是 `npx.cmd`）。

---

## 3. 接入后长什么样

### 3.1 12 个工具（实测 `tools/list`）

`*` 为必填参数；两份来源一致（包内静态解析 + 实测 `tools/list`）。

| 远端工具名 | 参数 |
| --- | --- |
| `maps_geo` | `address*`, `city` |
| `maps_regeocode` | `location*` |
| `maps_direction_driving` | `origin*`, `destination*` |
| `maps_direction_walking` | `origin*`, `destination*` |
| `maps_bicycling` | `origin*`, `destination*` |
| `maps_direction_transit_integrated` | `origin*`, `destination*`, `city*`, `cityd*` |
| `maps_distance` | `origins*`, `destination*`, `type` |
| `maps_text_search` | `keywords*`, `city`, `types` |
| `maps_around_search` | `keywords`, `location*`, `radius` |
| `maps_search_detail` | `id*` |
| `maps_weather` | `city*` |
| `maps_ip_location` | `ip*` |

### 3.2 注册进 ToolRegistry 之后

- **注册名** `mcp_<server>_<tool>`，此处即 `mcp_amap_<工具名>`；桥接层对两段分别限长（`MAX_SERVER_NAME = 20`、
  `MAX_TOOL_NAME = 39`，合计 `4+20+1+39 = 64` 对齐主流模型的函数名上限）。工具段 39 刚好容下
  `maps_direction_transit_integrated`（33），所以最长的 `mcp_amap_maps_direction_transit_integrated` 是 **42 字符**、未被截断。
- **全部只读**：`McpAgentTool.readOnly()` 取自 `read-only` 配置，`ToolRegistry.register` 的只读闸门因此放行；
  每次调用经 `SafetyInterceptor.check` 打一行审计日志（`[Audit] user=… tool=mcp_amap_… readOnly=true`）。
- **模型怎么用**：`IntentAgent.llmDetect` 把意图分类里的 `INVOKE_TOOL` 交给模型，提示词里自动拼上
  `renderToolCatalog()` 的输出——已注册的 `mcp_*` 工具名 + 描述。描述由桥接层拼成
  `(MCP:amap) <远端描述>（参数: address*(待解析地址), city；*为必填）`：参数名取自真实 `inputSchema`，
  必填加 `*`，参数说明截到 30 字并把英文逗号/括号换成全角（否则会打断提示词里的参数分隔）。
  **注册进 `ToolRegistry` 就等于进了提示词，不需要额外接线。** 一个 `mcp_*` 工具都没有时，
  提示词退化为「本次未接入任何 MCP 外部工具，不要使用 INVOKE_TOOL」。
- **越界拦截**：`IntentAgent.plausible` 要求 `INVOKE_TOOL` 的工具名以 `mcp_` 开头且 `toolRegistry.get()` 非空，
  模型编造工具名会被判为不可信并回落规则路径。
- **执行路径**：`ToolInvoker.invoke` → `SafetyInterceptor` → `McpAgentTool.execute` → `McpConnection.callTool`
  （远端 `result.isError=true` 或传输异常一律包成 `BusinessException(502, "外部工具 … 暂时不可用：…")`，
  `AgentEngine` 会把原因原样告诉用户）。

### 3.3 语义适配层（可选增强，尚未接线）

`mcp/AmapMcpAdapter`（`@Component`）把厂商真名映射成与厂商无关的语义名，并产出可直接拼进提示词的一行摘要：

| 语义名 | 远端工具 |
| --- | --- |
| `city_geocode` | `maps_geo` |
| `reverse_geocode` | `maps_regeocode` |
| `route_driving` / `route_walking` / `route_transit` / `route_bicycling` | `maps_direction_driving` / `_walking` / `maps_direction_transit_integrated` / `maps_bicycling` |
| `measure_distance` | `maps_distance` |
| `poi_text_search` / `poi_around_search` / `poi_detail` | `maps_text_search` / `maps_around_search` / `maps_search_detail` |
| `weather` / `ip_location` | `maps_weather` / `maps_ip_location` |

接口：`capabilityOf(remoteName)`、`discover(ToolRegistry)`、`registryName(registry, capability)`、`promptSummary(ToolRegistry)`。
`discover` 只从注册表里反推（前缀剥离 + 白名单校验），参数摘要优先取桥接层注入的真实 `inputSchema` 描述，
拿不到时回退到内置的、从包内 `build/index.js` 抄录的 schema。

**当前生产代码里没有调用方**（只有单测引用）：未接入时 `discover` 返回空列表、`promptSummary` 返回**空串**，
调用方原样拼接即零副作用。它是给「前端展示语义能力 / 提示词增强」预留的入口，接线时只需在拼提示词处追加
`adapter.promptSummary(toolRegistry)`。

### 3.4 边界：MCP 不参与规划产物

接入后模型能回答「查真实数据」类问题（`INVOKE_TOOL`：查 POI、算路径、查天气），但**规划（`PLAN_TRIP` /
`MODIFY_ITINERARY`）的候选清单仍来自内置/联网城市目录**（`data/travel-catalog.json` 与 `OnlineCityService`）：
`StructuredPlanner` 只从 `CityCatalog` 里选实体，坐标与价格一律取目录值。MCP 工具目前**没有**接进候选清单，
所以「行程里出现真实 POI 与真实票价」不在本次接入范围内。

仓库里已经有一个**按优先级消费 MCP 的真空位**，只是还没接线：`service/AmapDataService`（POI 文本搜索、驾车/步行路径）
按「已注册的 `mcp_*_maps_text_search` / `mcp_*_maps_direction_*` → 高德 REST（`AMAP_KEY`）→ 返回空列表/null 由调用方降级」
三级取数，并说明用 `ObjectProvider<ToolRegistry>` 懒解析避免构造环。**当前生产代码里没有任何调用方**（只有
`AmapDataServiceTest`），而且它按 REST 的 JSON 形状（`status`/`pois`）解析 MCP 返回的文本——真实返回体未验证，
接线时必须先核对。它和 `AmapMcpAdapter` 一样属于「本批留下的接缝」，不是已生效能力。

---

## 4. 排障与降级

### 4.1 降级总原则

**单个 MCP server 握手失败/超时只 `WARN` 跳过**，不影响应用启动、不影响内置工具、也不影响其它 server：

```
MCP 服务器 [amap] 接入失败，已跳过: timeout initialize
```

此时 `registry size = 0`、`AmapMcpAdapter` 不可用、`promptSummary` 为空串、应用功能与关闭 MCP 时完全一致。
握手/列举中途失败还会回收已拉起的 stdio 子进程，避免进程泄漏。

### 4.2 常见现象对照

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动日志 `接入失败，已跳过: timeout initialize`，同时控制台有 `AMAP_MAPS_API_KEY environment variable is not set` | 没给子进程 Key（包在模块加载期 `exit(1)`，连握手都到不了） | 设 `AMAP_MAPS_API_KEY` 后重启；注意不是 `AMAP_KEY` |
| 工具都注册成功，但一查就报 `外部工具 mcp_amap_maps_weather 暂时不可用：Get weather failed: INVALID_USER_KEY` | Key 无效/类型不对/未开通该服务。握手与 `tools/list` 不校验 Key，所以**注册成功不代表 Key 可用** | 换正确的「Web 服务」Key；从错误文本判断是高德侧拒绝还是网络问题 |
| 首次启动偶发超时，重试就好 | `npx` 冷启动要下载包 | `timeout-ms: 30000`；容器内提前 `npm i -g` 或预热 `~/.npm/_npx` 缓存 |
| Windows 上 `Cannot run program "npx"` / `CreateProcess error=2` | `npx` 是 `npx.cmd` 包装脚本，`ProcessBuilder` 不能直接 exec | 已在 `StdioMcpTransport` 自动补 `cmd /c`，**不用手写**；自定义命令是 `*.cmd`/`*.bat` 同样自动补 |
| 日志 `MCP 工具 mcp_amap_xxx 与已注册工具重名，已跳过` | 另一个 server 或内置工具已占用同名 | 改 `name`（server 别名）即可，注册名随之变化 |
| `MCP 未启用 (travel.mcp.enabled=false)，仅使用内置工具` | 总开关没开 | 设 `MCP_ENABLED=true` |
| 子进程活着但模型从不调工具 | 模型判断「这不是查外部数据」或意图识别走了规则路径（规则路径不发 `INVOKE_TOOL`） | 看 `Intent:` 日志里的 intent；INVOKE_TOOL 只有 LLM 路径产出 |

### 4.3 看哪几行日志

| 日志 | 含义 |
| --- | --- |
| `MCP stdio 子进程已启动: cmd /c npx -y @amap/amap-maps-mcp-server` | 命令解析 + `cmd /c` 补全生效 |
| `MCP 服务器 [amap] (…) 已接入，注册 N 个工具` | 握手与 `tools/list` 成功，N 应为 12 |
| `[Audit] user=… tool=mcp_amap_… risk=LOW readOnly=true` | 工具真的被调用了（`SafetyInterceptor` 审计） |
| `MCP 服务器 [amap] 接入失败，已跳过: …` | 降级路径，原因在冒号后 |

---

## 5. 已验证 / 未验证

### 5.1 已验证（本机实测，2026-11-16）

| 项 | 结论 |
| --- | --- |
| 包身份 | `version=0.0.8`、`bin={'mcp-amap': 'build/index.js'}`、依赖 `@modelcontextprotocol/sdk@1.0.1` |
| 无 Key | stderr `AMAP_MAPS_API_KEY environment variable is not set` + `exit(1)`；本项目侧握手超时 → `WARN` 跳过，`registry size = 0`，应用完全不受影响 |
| 占位 Key | `initialize` 成功（`mcp-server/amap-maps`）、`tools/list` 返回 **12** 个工具 |
| 无效 Key | `tools/call` 返回 `isError:true` + `INVALID_USER_KEY` → 本项目抛 `BusinessException(502)`（不是 JSON-RPC error） |
| 端到端注册 | 12 个工具全部注册为 `mcp_amap_*`，全部 `readOnly=true`；`AmapMcpAdapter` 12 个语义能力全部映射成功 |
| Windows | `cmd /c` 自动补全后 `npx` 能正常拉起 |
| 降级 | 关 MCP / 无 Key / 握手失败三种情况，应用启动与其它工具均不受影响 |
| **前端 JS API 真实渲染**（2026-11-19 实测） | 配好 `VITE_AMAP_KEY` + `VITE_AMAP_SECURITY_CODE` 后打开行程页：`window.AMap` 加载成功、`AMap.Map` 可用、`.amap-container` + `canvas` 已创建，共 **33 个高德域请求**（`webapi.amap.com` / `jsapi.amap.com` / `jsapi-data1..5` / `o4.amap.com` / `restapi.amap.com` 的 `jscode` 校验），页面无「地图加载失败」降级提示。**这是「前端地图真实渲染」从推测转为已验证** |
| **后端「Web 服务」Key 真实联调**（2026-11-19 实跑两次，`amap-real-key-probe.ps1` 结论均 **PASS**） | 独立实例 + 独立 H2：`/v3/place/text` `status=1` 返回 3 条 POI；`/v3/geocode/geo` 3/3 `status=1`（西湖 `120.130396,30.259242`、杭州东站 `120.212600,30.290851`、天安门 `116.397463,39.909187`，均为 GCJ-02），且地理编码点直接算路成功（11353 m）；`route-legs` 两次分别 **6/7** 与 **4/7** 段 `source=amap`（首段 10.7 km / 驾车 31 min，剩余段为 QPS 限流降级，见 §5.2）；GCJ-02 一致性：与高德 `/v3/assistant/coordinate/convert` 偏差 **0.13 m**、偏移量 **554.7 m**、`gcj02ToWgs84` 往返 **0 m**。证据：`.e2e/amap-key-20261119-143839/`、`.e2e/amap-key-20261119-143613/` |

### 5.2 未验证 / 已知缺口（不要当成已支持）

- **Key 类型踩坑（已定论，2026-11-19）**：同一枚「Web端(JS API)」Key 当后端 `AMAP_KEY` 用时，
  `/v3/place/text`、`/v3/geocode/geo`、`/v3/assistant/coordinate/convert` **全部**返回
  `status=0 info=USERKEY_PLAT_NOMATCH infocode=10009`，`PlaceService`/`RouteService` 随后静默降级为 `estimated`；
  此时后端日志**没有错误**（`parseRoute` 拿到 `status!=1` 就返回 null）。换成同应用下「服务平台 = Web服务」的
  另一枚 Key 后即 PASS（见 §5.1）。「安全密钥」只与前端 `VITE_AMAP_SECURITY_CODE` 配对，后端完全用不到。
- **高德 QPS 限流未产品化处理**：并发算路超过 Key 的并发配额时高德返回
  `status=0 info=CUQPS_HAS_EXCEEDED_THE_LIMIT infocode=10021`，`AmapDataService.parseRoute` 同样静默降级为
  `estimated`，且 `RouteService` 会把这次 `estimated` 结果缓存 10 分钟。本机实测 **8 个并发请求有 3 个 10021**，
  对应到一次两日游规划就是「7 段里 3 段 estimated」。这是**可观测性 + 重试**的缺口（没有 WARN、没有重试），
  不是 Key 或坐标问题；联调 PASS 的判定口径是「`amap` 段数 ≥1」，不要求全段真实。
- 12 个 MCP 工具只验证了「能被注册与调用到」；**各自的业务输出没有逐一核对**（例如 `maps_distance` 的
  `type` 取值、`maps_direction_transit_integrated` 的换乘结构），真实 Key 下的 MCP 链路（`MCP_ENABLED=true` +
  `AMAP_MAPS_API_KEY`）也还没有跑过。
- 真实行程里的 POI 采纳：见 3.4，MCP 尚未接进规划候选清单；行程条目坐标仍来自城市目录（WGS-84），
  高德数据体现在**路段距离/时长**上。

---

## 6. 相关文件

| 位置 | 作用 |
| --- | --- |
| `travel-agent-server/src/main/java/com/travel/agent/mcp/McpToolBridge.java` | 启动期接入、注册名生成、失败隔离、参数提示注入 |
| `travel-agent-server/src/main/java/com/travel/agent/mcp/StdioMcpTransport.java` | stdio 子进程、`cmd /c` 自动补全、按 `id` 配对、超时 |
| `travel-agent-server/src/main/java/com/travel/agent/mcp/McpConnection.java` | `initialize` / `tools/list` / `tools/call`，`isError` 转异常 |
| `travel-agent-server/src/main/java/com/travel/agent/mcp/McpAgentTool.java` | 包装成只读 `AgentTool`，异常转 `BusinessException(502)` |
| `travel-agent-server/src/main/java/com/travel/agent/mcp/AmapMcpAdapter.java` | 语义能力映射与 `promptSummary`（可选增强，未接线） |
| `travel-agent-server/src/main/java/com/travel/agent/service/AmapDataService.java` | POI / 路径三级取数（MCP → REST → 降级）；**当前无生产调用方** |
| `travel-agent-server/src/main/java/com/travel/agent/config/McpProperties.java` | `travel.mcp.*` 配置模型（`enabled` / `servers[]`） |
| `travel-agent-server/src/main/resources/application.yml` | `travel.mcp` 配置示例（默认关闭） |
| `travel-agent-server/src/test/java/com/travel/agent/mcp/` | `AmapMcpAdapterTest`、`McpStdioEnvTest` 等 |
| `.tools/amap-mcp-probe/` | 侦查原始记录与探针脚本（`FINDINGS.md` 等，不参与构建） |

架构位置与工具层闸门见 [架构](architecture.md) §6；`AMAP_KEY` / `MCP_ENABLED` / `AMAP_MAPS_API_KEY` 的运维口径见
[部署与运维](deployment.md) §5。

---

## 7. 真实 Key 联调（一键验证）

> 面向**已经拿到高德「Web 服务」Key、要证明真实 POI / 地理编码 / 路径 / GCJ-02 坐标在本项目里生效**的人。
> 工具是 `.e2e/amap-real-key-probe.ps1`（随仓库入库；`.tools/` 只放本地探针，不入版本库）。
> 它**不改产品代码**：用独立端口 + 独立 H2 文件库拉起一个后端实例，跑「登录 → 规划两日游 → 确认 → 路线段」，
> 再用同一枚 Key 直连高德 `/v3/place/text`、`/v3/geocode/geo`、`/v3/direction/driving`、
> `/v3/assistant/coordinate/convert` 诊断 POI / 地理编码 / 路径 / GCJ-02，把每一步的原始响应、统计与结论写到证据目录。
> 本节也补上了 §5.2「没有真实 Key 所以未验证」那部分的验收路径。

### 7.1 申请 Key：类型必须选「Web 服务」

1. 打开高德开放平台控制台 <https://console.amap.com/> →「应用管理 / 我的应用」→ 创建应用；
2. 添加 Key 时「服务平台」选 **Web 服务**（不是「Web端(JS API)」，也不是 iOS/Android）；
3. 复制 Key。若控制台给该 Key 配了 **IP 白名单**，本机联调要把出口 IP 加进去，或临时留空。

> ⚠️ **实测踩坑（2026-11-19）**：同一应用里「Web端(JS API)」和「Web服务」是**两枚不同的 Key**。
> 把 Web端 Key（通常还配着一枚「安全密钥」，那是 JS API 专用的）填进后端 `AMAP_KEY`，探针会以
> `status=0 info=USERKEY_PLAT_NOMATCH infocode=10009` 失败，且**后端日志没有任何错误**——
> 因为 `AmapDataService.parseRoute` 拿到非法返回就降级成 `estimated`。区分口径：
> 控制台里该 Key 的「服务平台」必须显示 **Web服务**；「安全密钥」只与前端 `VITE_AMAP_SECURITY_CODE` 配对，
> 后端完全用不到它。

三个 Key 变量不要混（与 §1.1 一致，这里只列本节能用到的）：

| 变量 | 代码读取点 | 用途 | 探针是否注入 |
| --- | --- | --- | --- |
| `AMAP_KEY` | `AmapProperties`（`travel.amap.key`，`application.yml` 的 `key: ${AMAP_KEY:}`） | 后端 REST：路径 `/v3/direction/driving`、POI `/v3/place/text`、地理编码 `/v3/geocode/geo` | **是**（核心） |
| `AMAP_MAPS_API_KEY` | 只出现在 `travel.mcp.servers[].env`，由高德 MCP server 子进程读取 | MCP stdio 子进程（12 个 `maps_*` 工具） | 否（探针强制 `MCP_ENABLED=false`，只验 REST Key） |
| `VITE_AMAP_KEY` | 前端 `travel-agent-web` 的 `amapLoader` | 前端地图渲染（JS API） | 否（JS API Key 不能给后端用） |

### 7.2 MCP server 配置（可选链路）

配置片段与 §2.2 完全一致，已写在 `travel-agent-server/src/main/resources/application.yml`：

```yaml
travel:
  mcp:
    enabled: ${MCP_ENABLED:false}
    servers:
      - name: amap
        command: ["npx", "-y", "@amap/amap-maps-mcp-server"]
        env:
          AMAP_MAPS_API_KEY: ${AMAP_MAPS_API_KEY:}
        read-only: true
        timeout-ms: 30000
```

开启方式见 §2.3。注意 `AmapDataService.routeAvailable()` 的判定是
「MCP 的 `maps_direction_driving` 工具已注册 **或** `AMAP_KEY` 非空」——
所以 MCP 与 REST 任一可用都会让 `/api/system/status` 的 `routeProvider=amap`。
探针为了把变量隔离清楚显式设 `MCP_ENABLED=false`：这样 `source=amap` 只可能来自 REST Key。

### 7.3 运行（一条命令）

```powershell
cd D:\travel-agent
powershell -ExecutionPolicy Bypass -File .e2e\amap-real-key-probe.ps1 -AmapKey "<你的Web服务Key>"
```

> 复用本机已经在跑的开发实例（例如 `run-dev.ps1` 起的 8080）：在命令后加 `-SkipStart -Port 8080`；该实例必须已经带 `AMAP_KEY` 启动。

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `-AmapKey` | 必填 | 高德「Web 服务」Key；只注入子进程环境变量 `AMAP_KEY`，不写盘 |
| `-Port` | `18090` | 探针实例端口（避开默认 8080） |
| `-SkipStart` | 关 | 复用该端口上已启动的实例，不启停任何进程（该实例必须已有 `AMAP_KEY`） |
| `-OutDir` | `.e2e\amap-key-<时间戳>` | 证据输出目录 |
| `-DryRun` | 关 | 只校验参数与本地配置（Key 格式/端口/curl/java/mvn/jar），不启动服务 |
| `-JarPath` | 自动找 | 指定后端 jar；缺省用 `travel-agent-server\target\travel-agent-server-*.jar`，没有先 `mvn -B -DskipTests package` |
| `-StartupTimeoutSec` | `180` | `/actuator/health` 轮询超时 |
| `-SkipRestDiag` | 关 | 跳过 4b 直连高德 REST 诊断（离线环境用） |

脚本给**子进程**设置的环境变量（均以代码实际读取的为准）：
`AMAP_KEY`、`AMAP_MAPS_API_KEY`（同一 Key；因 `MCP_ENABLED=false` 本次不生效，只保持变量口径一致）、
`JWT_SECRET`（每次随机生成，不读 `.env.local`）、`RATE_LIMIT_ENABLED=false`、`LLM_ENABLED=false`、
`WEATHER_REAL=false`、`MCP_ENABLED=false`、`DEMO_ACCOUNT=true`、`SERVER_PORT`、`SPRING_DATASOURCE_URL`（指向证据目录下的独立 H2）、
`INVOICE_DIR`、`PHOTOS_DIR`。
关掉 LLM 是为了让规划走规则引擎（不需要 LLM Key）；`/api/system/status` 不在 `SecurityConfig` 的 publicPaths 里，
所以脚本**先登录再自检**（第 2 步先于第 1 步执行，汇总表仍按 0/1/2/3/4a/4b 排列）。

### 7.4 预期输出与证据

真 Key 正常时，控制台应看到（节选）：

```
== [2] demo 登录：POST /api/auth/login ...
  PASS  2 demo 登录：username=demo，Cookie 已写入临时 jar（未落证据目录）

== [1] 系统能力自检：GET /api/system/status
        llmConfigured=False  amapConfigured=True  geoProvider=amap
        routeProvider=amap  routeHint=路线使用高德真实路径规划（打车费用仍为本地估算）
  PASS  1 系统能力自检：amapConfigured=true, geoProvider=amap, routeProvider=amap ...

== [3] 规划两日游 -> 确认 -> GET /api/trips/{id}/route-legs
        段数打点：总段数=7  amap=4  estimated=3  其它=0
  PASS  3 两日游规划与路线段：总段数=7，amap=4，estimated=3（已观察到 source=amap 的真实路径）

== [4] POI / 地理编码验证
  SKIP  4a 后端 POI/地理编码接口：当前没有该接口 —— ...
        place/text: status=1  info=OK  infocode=10000  条数=N（N≥1）
  PASS  4b 直连高德 REST 诊断：place/text status=1，返回 N 条 POI（N≥1），坐标均在中国境内合理范围（GCJ-02）...

== [4c] 地理编码验证：/v3/geocode/geo（与 AmapGeocodeClient 同一端点）
        杭州西湖：status=1  info=OK  infocode=10000  条数=1
        首个结果：浙江省/杭州市/西湖区  location=120.13xxxx,30.24xxxx（GCJ-02）
        杭州东站：status=1 ...；北京天安门：status=1 ...
        地理编码→路径：distance=xxxxm duration=xxx s（GCJ-02 坐标可直接算路）
  PASS  4c 地理编码直连诊断：3 个地址 status=1，坐标均在中国境内包围盒；地理编码点→路径串联成功（xxxx 米）...

== [4d] GCJ-02 转换一致性：后端 CoordinateTransform vs 高德 /v3/assistant/coordinate/convert
        高德 convert(gps→gcj02)：116.3975xx,39.9087xx
        仓库 wgs84ToGcj02：116.3975xx,39.9087xx
        与高德偏差 0.xx m；GCJ-02 偏移量 5xx m；往返误差 0.0x m
  PASS  4d GCJ-02 转换一致性：后端 wgs84ToGcj02 与高德 convert 偏差 0.xx m（≤5）；GCJ-02 偏移量 xxx m（100~1000）；往返误差 0.0x m（≤1）

==== 结论：PASS ====
```

- 段数取决于规划出的行程：默认消息「帮我规划杭州两日游，预算2000元，2个人」规则引擎会产 2 天、7 段；
  **判定口径是 `amap` 段数 ≥ 1**，不要求恰好 7。剩余 `estimated` 段的常见原因是高德并发限流
  `CUQPS_HAS_EXCEEDED_THE_LIMIT`（10021）被静默降级（见 §5.2、§7.6），不是 Key 或坐标问题。
- `taxiCost`（打车费）始终是本地估算，高德路径接口不返回价格（见 `RouteService` 口径说明），这不影响 PASS。

2026-11-19 真实「Web 服务」Key 实跑（两次结论均 **PASS**；主证据 `.e2e\amap-key-20261119-143839\`，
14:36 那次为 `.e2e\amap-key-20261119-143613\`）：

| 步骤 | 结果 | 实测值 |
| --- | --- | --- |
| 0 实例准备 | PASS | 独立 H2 + 端口 18097，`/actuator/health=UP`；结束时 PID 及其子树已停止，端口不再监听 |
| 1 系统能力自检 | PASS | `amapConfigured=true`、`geoProvider=amap`、`routeProvider=amap` |
| 2 demo 登录 | PASS | `username=demo` |
| 3 两日游规划与路线段 | PASS | 总段数=7、amap=**6**、estimated=1（首次实跑为 amap=4、estimated=3）；首段「滨江汉庭酒店 → 西湖风景区」10.7 km / 驾车 31 min。estimated 段数随高德并发配额波动，见 §5.2 |
| 4a 后端接口 | SKIP | 后端没有 POI/地理编码 REST 接口（如实记录，不伪造） |
| 4b 直连 POI | PASS | `/v3/place/text` `status=1`，3 条 POI（西湖风景名胜区 / 断桥残雪 / 湖滨公园），坐标境内 |
| 4c 地理编码 | PASS | 3/3 `status=1`：西湖 `120.130396,30.259242`、杭州东站 `120.212600,30.290851`、天安门 `116.397463,39.909187`；地理编码点→算路 11353 m |
| 4d GCJ-02 | PASS | 与高德 convert 偏差 **0.13 m**、偏移量 **554.7 m**、往返误差 **0 m** |
| 进程清理 | PASS | `cleanup.txt`：端口监听=False、进程存活=False |

证据目录（默认 `.e2e\amap-key-<时间戳>\`，约 0.2 MB，含独立 H2 文件与实例日志）：

| 文件 | 内容 |
| --- | --- |
| `summary.md` / `summmary.md` | PASS/FAIL 汇总表 + 关键数值 + 排查提示（两文件同内容，`summmary.md` 兼容需求原文拼写） |
| `01-system-status.json` | `/api/system/status` 原始响应（`amapConfigured` / `geoProvider` / `routeProvider` / `routeHint`） |
| `02-login.json` | 登录响应（`token` 已脱敏为 `<redacted>`；Cookie 只存临时目录，结束时删除） |
| `03-plan-response.json` / `04-confirm-response.json` | 规划与确认的原始响应（含 `planId` / `tripId`） |
| `05-route-legs.json` | 路线段原始响应（每段 `source` 字段即证据） |
| `06a-backend-poi-endpoint.txt` | 「后端没有 POI/地理编码 REST 接口」的事实记录 |
| `06-direct-rest-poi.json` / `07-direct-rest-direction.json` | 直连高德 REST 的原始响应（POI 条数/坐标；07 号在 4b 路径诊断执行时才有） |
| `08-direct-rest-geocode.json` | 直连 `/v3/geocode/geo` 的原始响应（3 个地址：状态码、province/city/district、GCJ-02 location） |
| `09-geocode-route.json` | 地理编码点直接喂给 `/v3/direction/driving` 的请求坐标、返回距离/时长（证明两条链路同为 GCJ-02） |
| `10-gcj02-transform.json` | GCJ-02 一致性：参照 WGS-84 点、高德 convert 结果、后端 `CoordinateTransform` 结果、三项偏差与判定 |
| `checks.json` / `run-meta.json` | 结构化判定、Key 指纹（SHA-256 前 8 位）、进程 PID 与清理结果 |
| `cleanup.txt` | 结束时本脚本 PID、端口是否仍监听、进程是否存活（证明无残留） |
| `server-stdout.log` / `server-stderr.log` | 实例日志（`MCP_ENABLED=false` 时日志会明确写「MCP 未启用」） |

判定口径与退出码：

| 退出码 | 含义 |
| --- | --- |
| `0` | PASS：实例就绪、登录成功、`route-legs` 至少一段 `source=amap`；未 `-SkipRestDiag` 时还要 4b（POI）成功、4c（地理编码→算路串联）成功、4d（GCJ-02 偏差 ≤5 m、偏移 100~1000 m、往返 ≤1 m） |
| `2` | 参数/配置问题（Key 格式、端口、工具缺失） |
| `3` | 验证 FAIL（典型：Key 无效 → 全部 `estimated`） |
| `4` | 实例启动失败（端口占用 / 健康检查超时 / `-SkipStart` 但端口没监听） |
| `1` | 未预期异常 |

> **`routeProvider=amap` 只代表「配置里读到了 Key」**（`AmapProperties.usable()` 只判断非空），**不代表 Key 有效**。
> 真正证明 Key 有效的是第 3 步 `source=amap` 段数 > 0、第 4b 步直连 `status=1`、第 4c 步地理编码点能直接算路、
> 第 4d 步与高德官方换算偏差在 5 米内。

### 7.5 当前接口事实（脚本不会伪造不存在的接口）

- **路线段**：`GET /api/trips/{id}/route-legs`（需登录）是唯一**按路段**暴露「真实/估算」来源的端点（`/api/system/status` 只给全局 `routeProvider`）：
  按天分组返回相邻行程点，字段含 `distanceKm` / `walkMinutes` / `driveMinutes` / `taxiCost` / `source`
  （`amap` = 高德真实路径，`estimated` = 本地 haversine 估算）。
  链路是 `RouteService.route()` → `AmapDataService.driving()`（MCP → REST）→ 失败降级 haversine。
- **POI / 地理编码**：后端**没有**对外 REST 接口。`AmapDataService.searchPoi()` 目前没有生产调用方
  （规划候选仍来自内置/联网城市目录）；`AmapGeocodeClient` 只被 `OnlineCityService` / `CityGeoLocator` 内部使用。
  因此脚本第 4a 步如实记 `SKIP`，第 4b 步改为**直连高德官方 REST**（与 `AmapDataService` 相同的
  `/v3/place/text`、`/v3/direction/driving`）诊断 Key 本身：记录返回条数，并按中国境内经纬度包围盒校验 GCJ-02 坐标。
- **地理编码（4c）**：直连 `/v3/geocode/geo`（与 `AmapGeocodeClient` 同一端点、同一 `location` 字段口径），
  解析「杭州西湖 / 杭州东站 / 北京天安门」三条地址；再把两个杭州地理编码点原样传给 `/v3/direction/driving`，
  能算出路径即说明两个接口**同一坐标系（GCJ-02）**，避免"坐标能返回但用不了"的假通过。
- **GCJ-02（4d）**：不重写公式。脚本用 JDK 单文件源码启动器 + `travel-agent-server/target/classes` **直接调用后端
  `CoordinateTransform.wgs84ToGcj02 / gcj02ToWgs84`**，把北京天安门参照点（WGS-84 `116.391275,39.907342`）
  的换算结果与高德 `/v3/assistant/coordinate/convert?coordsys=gps`（高德官方 GCJ-02 换算）对比：
  偏差 ≤5 m 说明公式与高德一致；偏移量 100~1000 m 说明确实做了 GCJ-02 偏移而不是恒等；
  `gcj02ToWgs84` 往返 ≤1 m 说明反算收敛。该步需要 `target/classes`（`mvn -DskipTests compile` 或打过 jar 即有）。
- **行程坐标**：行程条目经纬度来自城市目录（内部按 WGS-84 存储），不是每条 POI 的实时高德坐标；
  真实高德数据体现在 `source=amap` 的路段距离/时长上。

### 7.6 常见失败排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 第 1 步 `amapConfigured=false` / `routeProvider=estimated` | 进程没读到 `AMAP_KEY`（空 Key，或 `-SkipStart` 复用的旧实例没配） | 检查启动环境变量；用本脚本默认模式（自己起实例）重跑 |
| 第 3 步全部 `estimated`，第 4b 步 `INVALID_USER_KEY`（infocode 10001） | Key 无效/复制错/被删除/未开通「Web 服务」 | 回控制台核对 Key 与服务平台，重新复制 |
| 第 4b/4c/4d 步 `USERKEY_PLAT_NOMATCH`（infocode **10009**）——**2026-11-19 真实复现过两次** | **Key 类型错**：把 JS API（Web端）Key 用在了后端（该 Key 与前端 `VITE_AMAP_KEY` 是同一枚，控制台还配着「安全密钥」） | 换「Web 服务」类型 Key（同一应用下再添加一枚即可）；前端 `VITE_AMAP_KEY` 不能复用，安全密钥后端用不到 |
| 第 3 步部分 `estimated`，但 4b/4c/4d 全 PASS | **并发限流** `CUQPS_HAS_EXCEEDED_THE_LIMIT`（infocode **10021**）：规划并行算路超过 Key 的并发配额时高德直接拒绝，`AmapDataService.parseRoute` 拿到 `status!=1` **静默**返回 null → 该段降级 `estimated` 并被 `RouteService` 缓存 10 分钟，日志里没有 WARN。**2026-11-19 实测：8 个并发请求 3 个 10021** | 控制台提高并发配额；或给 `AmapDataService.direction` 加「10021 重试 + 退避 + 记 infocode」（当前未做，见 §5.2）。联调判定只看 `amap≥1` |
| 第 4b/4c/4d 步全部 `HTTP 0`、`curlExit=35`（SSL connect error）——**2026-11-19 在受限沙箱里真实复现过** | 本机到 `restapi.amap.com` 的 TLS 连接被拦/不稳定（代理、公司网关、运行沙箱），不是 Key 问题；此时后端也会把全部路段降级成 `estimated` | 放开网络后先手工 `curl.exe "https://restapi.amap.com/v3/place/text?key=<Key>&keywords=西湖"` 确认能拿到 JSON，再重跑脚本；纯离线环境加 `-SkipRestDiag`（4c/4d 一并 SKIP，不参与 PASS 判定） |
| 第 4c 步 `status=1` 但「地理编码→路径」失败 | 把 `location` 的经纬度顺序弄反（高德是「经度,纬度」），或两个点落到了不走车的区域 | 本脚本按「经度,纬度」原样透传；若手工复现，检查顺序与城市参数 |
| 第 4d 步 `找不到 target\classes\...CoordinateTransform.class` | 后端只留了 jar、没有编译产物 | 在 `travel-agent-server` 下 `mvn -DskipTests compile`；或先 `mvn -DskipTests package`（本脚本缺 jar 时也会自动打包） |
| 第 4d 步「与高德偏差 >5 m」 | `CoordinateTransform` 的公式/常量被改动，或与前端 `coords.ts` 不同步 | 按 §1.1.1 的坐标契约核对两侧实现；不要靠调阈值掩盖 |
| 第 4b 步 `USER_IP_ALIVE` / 白名单类错误 | Key 配了 IP 白名单，本机出口 IP 不在列表 | 控制台临时留空或加入出口 IP；公司网络注意出口 IP 会变 |
| 第 4b 步 `DAILY_QUERY_OVER_LIMIT` / 配额类错误 | 日调用量/并发配额用尽 | 等配额重置或提额；联调时少跑几次 |
| 第 4b 步网络不可达 / 超时（curl HTTP -1） | 本机访问不了 `restapi.amap.com`（代理/防火墙） | 配代理或换网络；后端此时会静默降级为 `estimated` |
| 第 3 步 PASS 但距离与地图上量出来的差几百米 | **坐标系差异**：高德返回 GCJ-02，本仓存储/API 统一 WGS-84，前端渲染再转回 GCJ-02 | 正常现象，见 §1.1.1；不要按「Bug」处理，也不要跨坐标系直接比距离 |
| 行程里没有 POI 的高德坐标 | 规划候选清单不接 MCP/POI 搜索（见 §3.4） | 属于当前边界；本节只验证「路线距离/时长真实化」 |
| MCP 链路 `接入失败，已跳过: timeout initialize` | MCP 没给 `AMAP_MAPS_API_KEY`（包在模块加载期 `exit(1)`） | 见 §4.2；注意那是另一个变量，与本节 `AMAP_KEY` 独立 |
| `无法加载文件 ... 因为在此系统上禁止运行脚本` | PowerShell 执行策略 | 命令里已带 `-ExecutionPolicy Bypass`；或 `Set-ExecutionPolicy -Scope Process Bypass` |
| 端口被占用 / 启动超时 | 18090 已被占，或首次 `mvn package` 太慢 | 换 `-Port`，或加 `-SkipStart`；启动失败时看 `server-stdout.log` |
| 脚本异常中断后是否有残留 java | 脚本只 `taskkill /PID <自己记录的 PID> /T`，不按进程名扫描 | 正常结束看 `cleanup.txt`（`process_alive_after_stop=False`）；异常中断按其中 PID 手工结束 |

### 7.7 不需要真 Key 的假 Key 自测（验证「优雅失败」）

```powershell
cd D:\travel-agent
# 1) 只校验参数与本地配置（不启动服务）
powershell -ExecutionPolicy Bypass -File .e2e\amap-real-key-probe.ps1 -AmapKey "fake-key-for-probe-0123456789abcdef" -DryRun
#    期望：exit 0，输出 PASS

# 2) 用同一假 Key 走完整流程
powershell -ExecutionPolicy Bypass -File .e2e\amap-real-key-probe.ps1 -AmapKey "fake-key-for-probe-0123456789abcdef"
#    期望：exit 3；第 0/1/2 步 PASS，第 3/4b/4c/4d 步 FAIL（INVALID_USER_KEY），第 4a 步 SKIP；
#          结束时 cleanup.txt 记录 pid、端口不再监听、进程存活=False，无残留 java
```

2026-11-19 复跑（Windows PowerShell 5.1、假 Key、未配任何真实高德 Key；本次新增 4c/4d）：

| 步骤 | 结果 | 实测值 |
| --- | --- | --- |
| 0 实例准备 | PASS | 独立 H2 + 独立端口，`/actuator/health=UP`，PID 记录并在结束时停止 |
| 1 系统能力自检 | PASS | `amapConfigured=true`、`geoProvider=amap`、`routeProvider=amap`（仅配置层面） |
| 2 demo 登录 | PASS | `username=demo` |
| 3 两日游规划与路线段 | FAIL | 总段数=7、amap=0、estimated=7（真实 Key 下应为 amap≥1） |
| 4a 后端 POI/地理编码接口 | SKIP | 当前没有该接口 |
| 4b 直连高德 REST 诊断 | FAIL | `status=0`、`info=INVALID_USER_KEY`、`infocode=10001` |
| 4c 地理编码直连诊断 | FAIL | 3 个地址均 `INVALID_USER_KEY`（10001） |
| 4d GCJ-02 转换一致性 | FAIL | `coordinate/convert` 同样被拒（10001） |
| 进程清理 | PASS | `cleanup.txt`：端口监听=False、进程存活=False |

### 7.8 与 §5 的关系

2026-11-19 已用真实「Web 服务」Key 按本节跑出 **PASS** 并保留证据目录，因此 §5.1 的
「真实 POI / 地理编码 / 路径距离与时长 / GCJ-02 坐标」已从**未验证转为已验证**；§5.2 只剩
「QPS 限流未做重试与可观测性」「MCP 12 个工具的业务输出未逐一核对」两类缺口。
MCP 那条链路仍要额外设 `MCP_ENABLED=true` + `AMAP_MAPS_API_KEY` 单独验（见 §2、§4）。
