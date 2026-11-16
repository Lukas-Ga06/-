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

### 5.2 未验证（不要当成已支持）

- **没有后端高德「Web 服务」真实 Key**，所以：POI / 路径 / 天气的**真实返回体结构**、**GCJ-02 坐标**、
  以及「MCP 场景下该 Key 是否还有额外限制」都只到「握手 + 工具清单 + 错误降级」级别。
- 12 个工具只验证了「能被注册与调用到」；**各自的业务输出没有逐一核对**（例如 `maps_distance` 的
  `type` 取值、`maps_direction_transit_integrated` 的换乘结构）。
- 真实行程里的 POI/路径采纳：见 3.4，MCP 尚未接进规划候选清单。

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