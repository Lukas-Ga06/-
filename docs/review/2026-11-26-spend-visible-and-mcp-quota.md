# 每轮花费可见 + MCP 配额窗口指标（2026-11-26）

> 快照日期：2026-11-26 ｜ 范围：V29 花费台账的**读取端**（前端运行详情）、MCP 逐 server 治理的
> **最后一条缺口**（每分钟配额窗口）与其告警
> ｜ 结论：**运行详情现在能看到「这一轮花了多少、卡在哪一项」；MCP 的分钟窗口用量/上限已可从
> `/actuator/prometheus` 与 `/api/system/status` 读到，且「没配额」与「0 次调用」不再混为一谈**。
> 后端完整 `mvn verify` 735 用例全绿，前端 557 用例全绿，规则 25 条 / 10 组 `promtool check rules` 通过。
> ｜ 动因：上一批（[花费落库](2026-11-25-spend-persistence.md) / [MCP 治理](2026-11-25-mcp-governance.md)）
> 把「写入」与「接口」做完了，但两处读取端仍是空的——前端不渲染 `spend`，指标缺配额窗口，
> 等于台账记了没人看、闸门限了没人知道。

## 0. 结论速览

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 运行详情渲染花费 | **已落地**：抽屉里新增「本轮花费」，字段缺省不补 0、老 run 不渲染空壳、触顶项中文警示 | 前端 5 个用例；`AgentRunsDrawer/index.test.tsx` |
| 金额口径 | `$` + 4 位小数；`0 < v < 0.0001` 显示 `<$0.0001`；`0` 照实显示 `$0.0000`（后端未配单价，不等于免费） | 用例覆盖上述三档 |
| MCP 分钟窗口指标 | **已补**：`mcp_server_calls_this_minute`、`mcp_server_quota`（tag `server`），配额未配置时 **NaN** | `McpGovernanceMetricsTest` 10/10；真实 `PrometheusMeterRegistry` 抓取 |
| 状态接口契约 | `mcpServers[]` 每行新增 `callsThisMinute`/`maxCallsPerMinute`，未配置配额为 **null**（不是 0）；旧构造器保留，纯增量 | `SystemControllerStatusTest` 13/13 |
| 告警 | 新增第 9 组 `travel-agent.mcp` 共 **3 条**：熔断打开、失败率 30%、配额 90%；规则总数 **25 条 / 10 组** | `promtool check rules` → `SUCCESS: 25 rules found` |
| Runbook | 新增 §20/§21/§22（症状 / 怎么确认 / 处置 / 何时算恢复），25 条 `runbook_url` 锚点全部命中 | 锚点脚本比对 25/25 |

一句话：**这一批只做读取端与「让数字能自证」的语义，没有新增业务能力，也没有改任何既有行为。**

## 1. 前端：运行详情渲染每轮花费

### 1.1 为什么不是「把 spend 塞进列表」

运行列表（最近 20 次）只回答「哪次成功/失败」；花费是**细节**，放在展开后的详情里才不挤占列表信息密度，
也避免一屏 20 行 × 8 个数字的噪音。位置固定在「任务链」之前——它是这一轮的总账，任务链与工具调用是分账。

### 1.2 渲染口径（评审重点）

| 情形 | 行为 | 理由 |
| --- | --- | --- |
| `spend` 缺省 / `null` / 字段全空且无 `exhausted` | **整块不渲染**（连标题都没有） | V29 之前的老 run 没有这条记录；写「无花费记录」是把「没记」和「记了 0」混在一起 |
| 字段部分缺省 | 只渲染后端真的给了的项，缺省项整项不出现 | 不补 0：0 是「真的有 0 元/0 次」，缺省是「不知道」 |
| `exhausted=llm_calls/tool_calls/retries/tokens/cost` | 追加一行 `⚠ 预算触顶：<中文项>` | 触顶是**降级原因**，必须与数字同屏；未知 tag 原样显示，不猜 |
| `costUsd = 0` | 显示 `$0.0000` | 真实记录的 0（未配置单价），美化掉会被读成「没花钱」 |
| `0 < costUsd < 0.0001` | 显示 `<$0.0001` | 四舍五入成 `$0.0000` 与「没花钱」不可区分 |
| 计数 | 千分位（`1,200`），`tabular-nums` 对齐 | 对账场景要竖着比 |

字段顺序：LLM 调用次数 → 工具调用次数 → 重试次数 → 输入/输出/合计 token → 费用。
样式复用 `.runs-section-title` 的既有节奏，新增 `.runs-spend` 一族类，用既有主题变量（暗色同样可读）。

### 1.3 契约对齐

`src/types/agent.ts` 的 `SpendView` 与 `docs/api/openapi.json` 的 `SpendView` 一一对应；
`RunDetail.spend` 是可选的（老服务端不返回该字段）。前端**没有**重新发明字段名，也没有把
`costUsd` 与任何本地「会话成本」口径混用——页面上的美元金额只来自后端 `agent_run_spend`。

## 2. MCP 每分钟配额：上一批唯一没落地的指标

### 2.1 缺口的本质不是「少一个数」

`McpGovernor` 内部一直有 `callsThisMinute`，但 `McpToolBridge` 的状态行没有它。补这个字段时真正的问题是：

> 未配置 `max-calls-per-minute` 时，闸门的窗口计数**恒为 0**——这与「本窗口真的 0 次调用」是同一个 0。

直接抄会得到「这个 server 很闲」的假事实，而真相是「配额这个机制没开」。所以这一批的做法是：

1. `McpGovernor#maxCallsPerMinute()` 暴露上限，让调用方能分辨「没配额」；
2. `McpServerStatus` 用 `Integer`（null = 没有这个事实）而不是 `int`；
3. 指标侧对 null 报 **NaN**（沿用 `outbox.status` 的既有纪律：查不到 ≠ 0）；
4. `/api/system/status` 同样给 `null`，不落 0。

旧的 8 参 / 4 参构造器保留，状态接口只是**新增两个键**，既有消费方不用改。

### 2.2 指标清单（全部 Gauge，抓取时现读闸门）

| Prometheus 名 | tag | 口径 | 不可观测时 |
| --- | --- | --- | --- |
| `mcp_server_enabled` | 无 | 总开关 1/0 | —（配置期已知，0 是真实答案） |
| `mcp_server_connected` | `server` | 接入成功 1 / 失败 0 | NaN |
| `mcp_server_tools` | `server` | 白名单过滤后的注册工具数 | NaN |
| `mcp_server_calls` | `server` | 放行并执行的累计调用数（含失败） | NaN |
| `mcp_server_failures` | `server` | 放行后的累计失败数（不含被拒） | NaN |
| `mcp_server_rejected` | `server` | 被闸门拒绝（白名单/配额/熔断） | NaN |
| `mcp_server_circuit` | `server`、`state` | 三态，命中 1 其余 0 | 三态同时 NaN |
| `mcp_server_calls_this_minute` | `server` | 当前 60 秒窗口已用 | NaN（含未配置配额） |
| `mcp_server_quota` | `server` | 配置的每分钟上限 | NaN（含未配置配额） |

为什么是 Gauge 不是 Counter：计数的唯一权威在 `McpGovernor`（一把锁同时维护计数与熔断机），
另开一个 Micrometer Counter 就有两份会漂移的真相；Gauge 现读快照，数值仍单调递增，
`rate()`/`increase()` 照常可用（进程重启归零按 counter 重置处理）。

### 2.3 状态接口

```json
{"name":"amap","connected":true,"toolCount":12,"lastError":null,
 "calls":7,"failures":1,"rejected":3,"circuit":"closed",
 "callsThisMinute":27,"maxCallsPerMinute":30}
```

未配置配额时后两项为 `null`。这是**纯增量**变更：键只增不减，旧字段含义未动。

## 3. 告警：3 条规则与它们的阈值理由

| 规则 | 表达式要点 | 阈值理由 |
| --- | --- | --- |
| `TravelMcpCircuitOpen` (warning) | `mcp_server_circuit{state="open"} == 1`，`for: 5m` | 三态 gauge 命中为 1；半开探测时 `open` 自动变 0，恢复即自动解除；连续 5 个冷却周期仍 open，要么探测持续失败、要么一直没人调用（同样等于工具不可用） |
| `TravelMcpServerFailureRateHigh` (warning) | 失败率 > 30% 且 `increase(calls[10m]) >= 5`，`for: 10m`，按 `(server, instance)` 取 | 30% 是「这个数据源基本不可用」的粗线；小样本保护避免 1 次失败 = 100%；分母只含放行调用，闸门收紧不会把它推高 |
| `TravelMcpQuotaPressureHigh` (warning) | `calls_this_minute / quota > 0.9`，`for: 5m` | 配额耗尽不是「慢」而是 503 直接拒绝；留 10% 余量做扩容/降并发；单分钟冲高可能只是突发，连续 5 个窗口才算结构性不够用；两条序列 NaN 时不命中（没配配额不误报） |

三条都写清了「什么时候算恢复」，其中两条特意写明**「没人用了」不等于「修好了」**——
这是同类告警最常见的假恢复。

## 4. 验证（真实命令与结果）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd travel-agent-server && mvn -B verify` | **735 用例，0 失败 / 0 错误 / 5 跳过**；jacoco 覆盖率门槛通过；checkstyle 0 violation；spotbugs 0 bug；BUILD SUCCESS（2:34） |
| 后端定向 | `-Dtest='McpGovernanceMetricsTest,McpGovernorTest,McpBridgeTest,SystemControllerStatusTest,OutboxStatusMetricsTest,TravelMetricsTest'` | 43/43（含新增配额两条序列用例） |
| 前端全量 | `cd travel-agent-web && npm test` | **54 文件 / 557 用例全过**（新增 `AgentRunsDrawer` 5 例） |
| 前端门禁 | `npm run typecheck` / `lint` / `format:check` | typecheck 0；lint 0 error（47 条既有 warning）；format 全过 |
| 端到端（隔离） | `npm run e2e:isolated` | **14/14 通过**（a11y 5、隔离后端自证 1、地图 vendor 3、规划流 2、冒烟 3）；未新增「运行详情」专项用例 |
| 首屏预算 | `npm run build:check` | 首屏 JS **598,949 B / 600,000 B**（余 1,051 B）、CSS 57,701 / 65,536 B，均通过 |
| 规则语法 | `node scripts/alerts-rules-extract.mjs` + `.tools/prometheus/prometheus-3.16.0.windows-amd64/promtool.exe check rules` | **10 组 / 25 条**，`SUCCESS: 25 rules found` |
| 锚点 | 脚本比对 `runbook_url` 与 `runbook.md` 标题 | 25/25 命中，0 断链 |
| 文档 | `node scripts/docs-check.mjs` | 96 个 md / 862 条相对链接 / 448 条命令，**0 断链**（1 条既有 Maven goal 警告） |

## 5. 边界（没做的与别误读的）

1. **首屏余量只剩 1,051 B**：`AgentRunsDrawer` 在同步 chunk 里，本次新增约 1 KB。预算仍通过，
   但下一批任何「往主 chunk 加组件」的改动都会撞线——要么先做懒加载，要么别再往同步路径加东西。
2. **未做真机/像素级验收**：这次用真实 `global.css` 做了静态核对（520px 抽屉、390px 窄屏、暗色三档），
   但那是 CSS 核对页，不是运行中的 App 截图；`e2e:isolated` 14/14 通过，但没有一条用例会打开运行记录抽屉，
   所以「抽屉里的花费区块在真实浏览器里长什么样」仍只有组件级测试与静态核对。
3. **MCP 指标只有单测 + 真实 registry 抓取**：没有起真实 Prometheus 抓一次（与 `outbox.status` 同口径的边界），
   也没有真实高德 10021 压测下的配额曲线。
4. **告警仍无真实 webhook**：三条新规则与既有 22 条一样，只能证明「规则可被 Prometheus 解析」，
   不能证明「人收到了」；值班人与渠道见 [告警路由](../operations/alert-routing.md) §5/§6。
5. **熔断 open 不会因时间自动关闭**：状态机只在调用时推进，所以「闸门还开着但没人经过」会持续 firing；
   Runbook §20 写了判读方法，彻底消掉这类假阳性需要改 `McpGovernor`（本轮未做）。
6. **`mcp_server_rejected` 仍混三种拒绝原因**（白名单/配额/熔断）：拆 `reason` tag 会改契约与基数，
   本轮用 `[MCP-Audit]` 日志的 `minuteCalls` 兜底。
7. **`McpGovernor` 计数是 `int`**：21 亿次会溢出（`rate()` 会当 counter reset）。实际不可能达到，记录在案。

## 6. 相关文档

- 花费契约与落库：[花费落库](2026-11-25-spend-persistence.md)、[`data-model.md` §4.6](../guide/data-model.md)
- MCP 治理能力：[MCP 逐 server 治理](2026-11-25-mcp-governance.md)、[`mcp-amap.md`](../guide/mcp-amap.md)
- 指标与告警口径：[可观测性 §2.1/§4](../guide/observability.md)、[Runbook §20–§22](../operations/runbook.md)
- 规则文件：[`alerts.yml`](../operations/alerts.yml)、路由与值班：[告警路由](../operations/alert-routing.md)