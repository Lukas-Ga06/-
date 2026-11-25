# Agent 统一花费预算验收记录（2026-11-25）

> 快照日期：2026-11-25 ｜ 范围：第二阶段「Agent 能力增强」的第一项——统一花费预算（调用次数 / 工具次数 / 重试 / token / 估算费用），
> 叠加在既有 deadline 预算上 ｜ 环境：Windows + MSYS2 / JDK 17 / Maven 3.9.16 / H2 内存测试上下文 ｜
> 结论：**后端全量 `mvn -B verify` 通过（708 tests / 0 failures / 3 skipped，Checkstyle 0、SpotBugs 0、JaCoCo 达标）；
> 花费预算语义、预算层记账、研究循环门禁、真实 HTTP 请求降级均有自动化覆盖**。
> 真实供应商 token 记账、每轮花费落库、MCP 逐 server 治理仍待办，见 §4。

## 1. 范围与动因

时间预算（P1）解决的是「慢」，不解决「贵」。同样的 30s 里：

| 放大路径 | 表现 | 时间预算能否拦住 |
| --- | --- | --- |
| 研究循环换参数重试 | 每步一次决策 + 一次工具，直到 `MAX_STEPS` | 只按剩余时间拦，30s 内能跑满步数 |
| 结构化规划带反馈重试 | 首轮失败 → 带更长的反馈提示词再问一遍 | 不能，重试本身只查剩余时间 |
| 审核重规划 | 每轮 = 诊断 + 修复 + 复审，最贵的一档 | 只能靠 `MIN_REVIEW_MS` 粗粒度拦 |
| 工具风暴 | MCP 高德算路/天气逐段调用 | 完全不能 |

而 deadline 到点时，这些调用**已经花掉了钱**。因此新增与时间正交的第二个维度。

| 工作流 | 落地内容 | 主要位置 |
| --- | --- | --- |
| 花费预算本体 | 五类上限 + 原子计数 + 触顶原因描述 + 用量摘要 | `agent/AgentSpend.java` |
| 预算接线 | 时间 deadline 与花费预算同一对象；`chat` 唯一 LLM 记账口 | `agent/AgentBudget.java`、`config/AgentBudgetProperties.java`、`controller/AgentController.java` |
| 用量回传 | 从响应 `usage` 解析真实 token，一次性取走 | `llm/LlmUsage.java`、`llm/LlmClient.java`、`llm/OpenAiCompatibleLlmClient.java`、`llm/DelegatingLlmClient.java` |
| 工具收口 | 工具次数在门面记账；触顶跳过并降级（返回 `null`） | `tool/ToolInvoker.java` |
| 阶段门禁 | 研究循环 / 显式工具意图 / 润色 / 结构化规划重试 / 审核重规划 | `agent/AgentToolLoop.java`、`agent/AgentEngine.java`、`agent/StructuredPlanner.java`、`agent/PlanReviewLoop.java`、`service/PlanNarrationService.java` |
| 降级可观测 | 用户文案区分两种预算、DEGRADE 任务带具体原因、每轮花费日志、`travel.agent.spend` 指标 | `agent/AgentContext.java`、`agent/AgentEngine.java`、`observability/TravelMetrics.java` |
| 配置面 | 6 个 `AGENT_SPEND_*`（yml / compose / env 样例 / 部署与生产配置文档） | `application.yml`、`docker-compose.yml`、`.env.production.example`、`docs/` |

## 2. 关键决策与不变量

### 2.1 记账只有两个出口，新增阶段不可能漏

- **LLM**：`AgentBudget#chat` 是各 Agent 阶段唯一的模型调用入口。调用前判限额、调用后 `recordLlmCall()` +
  `recordUsage(client.takeLastUsage())`。只要新阶段用 `budget.chat`，就自动计费；绕过它直接 `llmClient.chat`
  的调用点会在 §4 列明（都是不经 Agent 预算的辅助服务）。
- **工具**：`ToolInvoker#invoke` 是所有工具调用的门面（含审计、SSE 事件、落库追踪），次数计数放在这里。
- 记账顺序刻意「宁少不重」：调用前先 `takeLastUsage()` 清掉线程上可能残留的用量，避免把上一轮（或探测调用）
  的 token 记到本轮。

### 2.2 软上限：一次在途调用可以越界，下一次一定被拒

次数类（LLM/工具/重试）在发起前判定；token 与费用只能从响应里读到，因此：

- 一次调用可能刚好把用量推过上限，越界量被**一次调用**封顶；
- 下一次调用必定被拒，且拒绝时不再打供应商（`AgentBudgetSpendTest#llmCapRefusesBeforeCallingClient` 断言
  「触顶后客户端调用次数不再增长」）。

这是同步调用下唯一诚实的选择：把「响应前不知道 token」当成已知边界写进文档，好过用字符数估算去伪造一个精确额度。

### 2.3 token 与费用只认真实数据

- token 来自供应商 `usage`（兼容 `prompt_tokens/completion_tokens` 与 `input_tokens/output_tokens`）；
  没有 `usage` 就是 `LlmUsage.NONE`，不按字符数估算。
- 费用 = token × 配置单价；`LLM_PROMPT_PRICE_PER_1K_USD` / `LLM_COMPLETION_PRICE_PER_1K_USD` 都为 0 时费用恒为 0，
  费用上限**永远不会触发**（`AgentSpendTest#costCapIsInertWithoutPrices`）。用错的价目表卡用户请求，比不卡更危险。
- 与 `travel.llm.tokens` / `travel.llm.cost.usd` 同一口径：预算里的花费与成本看板不会互相打脸。

### 2.4 触顶的语义是「降级并说清」，不是失败

| 触顶项 | 行为 | 用户可见结果 |
| --- | --- | --- |
| LLM 调用 / token / 费用 | 阶段门禁拒绝并在阶段边界留痕；`chat` 兜底返回 `null`（走既有降级） | 回复写明「花费预算耗尽（具体项/已用/上限）」，并说明跳过了哪个阶段 |
| 工具调用 | `ToolInvoker` 跳过该工具、返回 `null`，规划侧按「查不到」处理（少排一个点、路线成本记 0） | 回复写明「未执行工具 X」 |
| 重试 | 保留首轮/当前结果，不再重试 | 结构化规划保留部分有效结果；审核保留当前判定（`passed=false` → 确认门仍要求用户显式接受风险） |

**这一条是本批修掉的真实缺陷**：最初的实现让 `ToolInvoker` 在触顶时抛 `IllegalStateException`，结果一次普通
三日游请求（确定性规划路径内部工具调用 20+ 次）直接 500/`FAILED`——`AgentBudgetIntegrationTest` 当场把它打红。
现改为跳过 + 降级，并按真实用量把默认工具上限校准到 200（3 天 ≈ 20–30 次，7 天 ≈ 60+）。

### 2.5 不落库是刻意取舍

`agent_run` 没有花费列，加列需要新迁移（V29）与真实 MySQL 验证；本批不做。每轮花费通过
`Agent 本轮花费: runId=... llm=... tool=... retry=... tokens=... cost=... exhausted=...` 的 INFO 日志回查，
并用 `travel.agent.spend{result,limit}` 做聚合告警。**指标只记结论、不把用量写进 tag**（基数纪律）。

## 3. 验证证据（本机实测）

命令：`cd travel-agent-server && mvn -o -B verify`

| 门禁 | 结果 |
| --- | --- |
| 后端测试 | **708 tests / 0 failures / 0 errors / 3 skipped**（3 个真实 MCP/MySQL 用例按设计跳过） |
| Checkstyle | 0 violations |
| SpotBugs | BugInstance 0 / Error 0 |
| JaCoCo | `All coverage checks have been met` |

新增/改动的定向用例：

| 用例 | 覆盖点 | 结果 |
| --- | --- | --- |
| `AgentSpendTest`（9） | 五类上限各自生效；0 = 禁止、负数 = 不限；token 缺失不估算；未配单价时费用上限不触发；触顶原因文案与 `summary` 口径 | 9/9 |
| `AgentBudgetSpendTest`（7） | `chat` 自动记账（次数 + usage）；触顶后不再打客户端；降级留痕带花费原因；`remainingMs` 不伪装成时间耗尽；调用方自有读超时被「要求值 vs 剩余预算」封顶；未启用时保持两参调用 | 7/7 |
| `AgentToolLoopSpendTest`（4） | 工具次数触顶 → partial 收尾且保留已收集事实；LLM 次数触顶 → 停止决策；未启用花费预算时行为不变 | 4/4 |
| `AgentSpendIntegrationTest`（2） | `travel.agent.budget.spend.*` 嵌套绑定真的进了预算实例；把工具上限压到 2 后**真实 `/api/agent/chat` 仍返回可用结果**、回复与 warnings 写明「花费预算 + 工具调用」且状态不是 `FAILED` | 2/2 |
| `LlmUsageMetricsTest`（+2） | `recordUsage` 同时把 usage 挂到 `takeLastUsage`，且只消费一次；无 usage 时零用量 | 7/7（整类） |
| `AgentBudgetIntegrationTest`（2，回归） | 时间预算耗尽路径未被花费预算改变（回复仍含「时间预算」与降级阶段） | 2/2 |
| `StructuredPlanner*` / `PlanReviewLoop*` / `PlanNarration*` / `AgentToolLoopTest` / `AgentBudgetDisabledTest` | 重试门禁与润色接线的回归 | 全部通过 |

## 4. 未验证与已知边界

1. **真实供应商 token 记账没有端到端跑过**：测试环境的 LLM 是关闭/桩实现，`usage` 解析链路由
   `LlmUsageMetricsTest`（构造响应 JSON）覆盖。真实 DeepSeek 的 `usage` 字段一旦变化，只有线上日志能发现——
   本批没做真实批量验收，也没有 Key。
2. **每轮花费不落库**：想按 runId 查「这轮到底花了多少」只能查日志（有 runId），没有 SQL 可查。
   需要落库就得加列 + 新迁移 + 真实 MySQL 验证，按现有迁移纪律单独立项。
3. **4 处辅助调用不在预算内**（单发、无循环，风险低于 Agent 阶段，但口径上仍要说明）：
   `RagService#answer`（知识问答接口）、`OnlineCityService#loadViaAmap` / `#loadViaLlmCoords`（城市目录生成）、
   `TripReviewService`（行后回顾）。润色已在本批接入。
4. **费用上限依赖价目表人工维护**：单价配错，费用上限就跟着错；`max-cost-usd=1.0` 在默认单价 0 下是空转值。
5. **并发口径**：`AgentSpend` 用原子计数，可跨线程（Coordinator 并行任务）累加；但「一轮」的定义是
   `AgentContext` 的生命周期。SSE 与同步接口各建各的预算，同一 run 的续传不会重新计费，也不会重复计费。
6. **没有做真实的多实例/长时间压测**：指标 `travel.agent.spend` 是进程内累加，多实例下按实例聚合，与既有指标一致。

## 5. 下一步（按真实缺口排序）

1. **MCP 逐 server 治理**：白名单 / 熔断 / 配额 / 审计——花费预算只告诉我们「调了多少次」，
   告诉不了「哪个 server 在拖后腿」。
2. **每轮花费落库**：`agent_run` 增加花费快照列（或独立 `agent_run_spend` 表）+ 新迁移，让执行台账能直接回答花费问题。
3. **真实 Embedding 与 POI 质量验收**：与花费口径无关，但同属第二阶段「数据真实性」。
4. 阶段三：Outbox / ABANDONED / 租约清扫的三个低基数指标与告警（目前仍只有日志）。