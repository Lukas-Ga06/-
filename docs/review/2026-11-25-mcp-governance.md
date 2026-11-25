# MCP 逐 server 治理（白名单 / 熔断 / 配额 / 审计）

> 快照日期：2026-11-25 ｜ 范围：MCP 桥接层，按 server 生效的四项治理能力
> ｜ 结论：**四项能力全部落地，默认全关（不写配置即与治理前逐字一致）**；
> 定向测试 21/21 通过（`McpGovernorTest` 5、`McpBridgeTest` 4、`SystemControllerStatusTest` 12），
> 后端完整 `mvn verify` 见 §4 的最终读数。
> ｜ 动因：[花费预算评审](2026-11-25-spend-budget.md) §5 第 1 项——花费预算只回答「调了多少次」，
> 回答不了「哪个 server 在拖后腿、坏了还在被反复调用」。

## 0. 结论速览

| # | 问题 | 结论 |
| --- | --- | --- |
| 1 | 能不能只让模型看到指定工具 | 能：`allow-tools` 白名单在**注册期**过滤（模型看不到），调用期再做一次防御性校验（403） |
| 2 | 一个 server 连续失败会不会一直烧调用 | 不会：`failure-threshold` 打开熔断，冷却期内快速失败（503），不再白等一次超时 |
| 3 | 能不能压住上游 QPS（高德 10021） | 能：`max-calls-per-minute` 是跨 run 的 server 级 60s 窗口配额 |
| 4 | 运维能不能看到哪个 server 出问题 | 能：`/api/system/status` 的 `mcpServers[]` 新增 `calls/failures/rejected/circuit`；每次调用一行 `[MCP-Audit]` 日志 |
| 5 | 默认行为变了没有 | 没有：四个配置项默认关闭/不限，治理前行为不变（专门有测试守住） |
| 6 | 计数能不能长期当账本 | **不能**：进程内计数、重启归零，用于实时保护与排障；长期花费仍看 `agent_run`/日志（每轮花费落库是下一项） |

## 1. 背景

P2 花费预算把「一次 Agent 调用的花费」收口成 LLM 调用/工具调用/重试/token/费用五项，
但它只有**全局计数**。真实故障的形状不是这样：

- 高德 MCP 分支没有 REST 那套 `10021` 退避重试，突发算路先撞限流、打 ERROR、再退回 REST
  （见 [mcp-amap](../guide/mcp-amap.md) §5.2 的记录）；
- 一个挂掉的 server 会让每次 `tools/call` 都等满 `timeout-ms`，模型看不到「这个 server 已经连续失败」，
  只会换个参数再试——工具次数被烧掉，时间预算也被烧掉；
- 排查时只能翻 `[Audit]` 日志，无法回答「这个 server 这次运行被调了多少次、失败几次、是不是已经熔断了」。

因此治理必须**按 server**：白名单控制暴露面，熔断止损，配额保护上游，审计给出可读结论。

## 2. 设计

闸门在 `McpGovernor`（每个 server 一个实例），调用链：

```
AgentToolLoop / INVOKE_TOOL
  └─ ToolInvoker（只读门禁 / 审计参数 / 花费预算工具计数 / 追踪落库）
      └─ McpAgentTool.execute
          ├─ governor.beforeCall(远端工具名)   ← 白名单 → 配额 → 熔断（放行才计数）
          ├─ connection.callTool(...)          ← 真正的外部请求
          └─ governor.recordSuccess/recordFailure + 一行 [MCP-Audit]
```

口径（与花费预算对齐）：

1. **发起前计数**：放行即计入 `calls` 与窗口配额；超时/失败的尝试同样消耗上游配额，必须计入；
2. **被拒不计调用数**：白名单/熔断/配额拒绝只增加 `rejected`，不伪造一次外部调用；
3. **半开只有一个探测**：冷却结束放行一次探测，并发调用在探测期间一律拒绝；
   探测成功闭合、失败立即重新打开（不必再等满一个冷却周期）；
4. **熔断阈值默认关闭**：`failure-threshold=0` 时连续失败只累计不熔断——避免默认配置改变既有部署行为；
5. **白名单影响注册数**：`status.toolCount` 是过滤后的数量；`mcpHint` 与工具清单自然保持一致，提示词不会推荐一个模型根本调不到的工具；
6. **被拒的调用抛 `BusinessException`（403/503）**：研究循环把它记成失败观察让模型自纠；
   不返回 `null`（那会被当成「查到了空结果」，属于伪造事实）。

## 3. 配置

`travel.mcp.servers[]` 新增四个字段，全部有默认值：

| 字段 | 默认 | 语义 |
| --- | --- | --- |
| `allow-tools` | 空（不限） | 远端原始工具名白名单；为空时全部工具可注册 |
| `max-calls-per-minute` | `0`（不限） | 跨 run 的固定 60s 窗口配额 |
| `failure-threshold` | `0`（关闭） | 连续失败达到该值打开熔断 |
| `cooldown-ms` | `60000` | 熔断打开时长；到点放行一次半开探测 |

示例与行为说明见 [mcp-amap](../guide/mcp-amap.md) §2.4；`application.yml` 的高德 server 下有注释样例。

## 4. 验证证据（2026-11-25，本机）

定向测试（`travel-agent-server`）：

```
mvn -B -Dtest='McpGovernorTest,McpBridgeTest,SystemControllerStatusTest' -DfailIfNoTests=false test
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

覆盖点：

- 默认配置下 50 次调用+50 次失败不熔断、不限次（守住「默认行为不变」）；
- 白名单 trim 匹配、非白名单拒绝为 403、`rejected` 计数、被拒不消耗 `calls`；
- 配额用尽 503、窗口滚动恢复、`callsThisMinute` 口径；
- 熔断：阈值前闭合 → 阈值打开 → 冷却期快速失败 → 半开探测 → 探测失败重开 → 探测成功闭合；
- 审计摘要只含低基数计数，不含参数；
- 桥接端到端：HTTP stub 两个工具只注册白名单内的一个，调用后 `status()` 实时显示 `calls=1`、`circuit=closed`；
- 状态接口：治理字段按行输出，未启动/未启用时仍走旧四参形态（默认值 0/closed）。

日志样例（真实测试输出）：

```
[MCP-Audit] server=stub tool=web_search outcome=success durationMs=4 state=closed calls=1 failures=0 rejected=0
MCP 工具 [stub] web_delete 不在 allow-tools 白名单内，跳过注册
```

鉴权边界：`/api/system/status` 需要登录（见其控制器契约），治理计数只暴露数字与状态枚举，
**不含**上游错误原文、参数或 Key。

## 5. 未验证 / 边界

- **真实高德 10021 复测未做**：配额机制是确定性的，但「设置多少才不撞 10021」需要真实 Key 与并发压测，
  属于外部输入；本轮只验证机制本身。
- **计数不持久化**：重启归零，不做多实例聚合；跨实例的 server 级配额需要 Redis 之类的共享计数，
  当前单实例部署下不值得引入。
- **未加 Micrometer 指标**：审计先以日志 + 状态接口落地；指标化与「阶段三 3 个低基数指标」一批做，
  避免为 MCP 单独开一套 tag 体系。
- **半开探测若线程被强行终止**（不跑 `finally` 的极端情况）会停在 `half_open`，下一次调用会被拒绝；
  进程重启即恢复。正常异常路径都会走 `recordFailure`。

## 6. 下一步

1. 每轮花费落库（`agent_run_spend`）：把「这一轮花了多少」变成可查询台账（§5 第 2 项）；
2. 阶段三：Outbox / ABANDONED / 租约清扫的三个低基数指标与告警（§5 第 4 项）；
3. 真实 Embedding 与 POI 质量验收（§5 第 3 项，需要真实 Key）。