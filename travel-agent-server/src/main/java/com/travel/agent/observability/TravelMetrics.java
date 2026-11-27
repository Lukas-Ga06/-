package com.travel.agent.observability;

import com.travel.agent.agent.AgentSpend;
import com.travel.agent.llm.LlmProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务指标集中定义（T5 可观测性）。
 *
 * <p>为什么集中在一处：指标名与 tag 值是<b>对外契约</b>——看板、告警、Runbook 都按它们写死。
 * 散落在各处的字符串字面量一旦改名，就是「面板静默变空、告警永远不响」这类最难发现的事故。
 * 因此所有本迭代新增的业务指标都在这里定义名称常量与 helper，埋点处只调用 helper。
 *
 * <p>基数纪律：只允许枚举型 tag（result/type），<b>禁止</b>把 userId/runId/tripId/query 等
 * 无限取值放进 tag。用户/运行维度的排障走日志与 trace（traceId/runId），不进指标。
 *
 * <p>故障隔离：指标是旁路观测，任何 helper 抛出 RuntimeException 都只记 debug 日志并吞掉，
 * 绝不影响业务返回值、事务或日志语义（与 {@code AgentStageTimer}、{@code AgentToolLoop} 一致）。
 *
 * <p><b>MCP 治理计数为什么用 Gauge 而不是 Counter：</b>calls/failures/rejected 的唯一权威是
 * {@link com.travel.agent.mcp.McpGovernor}——它用一把锁同时维护计数与熔断状态机。若在埋点处再开一个
 * Micrometer Counter 平行累加，就有了两份会漂移的真相（异常路径漏记 → 指标比闸门少；重试路径重复记 →
 * 比闸门多），而告警与 {@code /api/system/status} 必须读到同一个数。Gauge 在每次抓取时现读闸门快照：
 * 数值单调递增的语义不变（Prometheus 侧照常 {@code rate()}/{@code increase()}，进程重启归零也按 counter
 * 重置处理），只是 {@code # TYPE} 标成 gauge。代价是闸门不可观测时只能报 NaN——这正是下一条纪律。
 *
 * <p><b>MCP 指标的 NaN 纪律：</b>MCP 未启用（{@code travel.mcp.enabled=false}）、桥接尚未启动
 * （异步 {@code SmartLifecycle}，启动瞬间还没有 server 列表）或 server 不在当前快照里时，逐 server 的
 * Gauge 一律报 <b>NaN</b>，绝不用 0 冒充「没调用/没失败」：NaN 不会命中 {@code > 0} / {@code == 1}
 * 这类告警，曲线上是断点，能区分「查不到」与「真的是一次都没有」。唯一敢报 0 的是
 * {@link #MCP_ENABLED}——开关状态在配置期已知，0 就是真实答案。
 *
 * <table>
 *   <caption>本类定义的指标</caption>
 *   <tr><th>Micrometer 名</th><th>Prometheus 名</th><th>类型</th><th>tag</th></tr>
 *   <tr><td>travel.plan.outcome</td><td>travel_plan_outcome_total</td><td>counter</td>
 *       <td>result=success|degraded|failed</td></tr>
 *   <tr><td>travel.confirm.outcome</td><td>travel_confirm_outcome_total</td><td>counter</td>
 *       <td>result=success|conflict|failed</td></tr>
 *   <tr><td>travel.rag.retrieval</td><td>travel_rag_retrieval_total</td><td>counter</td>
 *       <td>result=hit|miss</td></tr>
 *   <tr><td>travel.llm.tokens</td><td>travel_llm_tokens_total</td><td>counter</td>
 *       <td>type=prompt|completion</td></tr>
 *   <tr><td>travel.llm.cost.usd</td><td>travel_llm_cost_usd_total</td><td>counter</td>
 *       <td>无（金额累加，单位美元）</td></tr>
 *   <tr><td>travel.agent.spend</td><td>travel_agent_spend_total</td><td>counter</td>
 *       <td>result=within|exhausted、limit=none|llm_calls|tool_calls|retries|tokens|cost</td></tr>
 *   <tr><td>mcp.server.enabled</td><td>mcp_server_enabled</td><td>gauge</td><td>无</td></tr>
 *   <tr><td>mcp.server.connected</td><td>mcp_server_connected</td><td>gauge</td><td>server=&lt;配置名&gt;</td></tr>
 *   <tr><td>mcp.server.tools</td><td>mcp_server_tools</td><td>gauge</td><td>server=&lt;配置名&gt;</td></tr>
 *   <tr><td>mcp.server.calls</td><td>mcp_server_calls</td><td>gauge</td><td>server=&lt;配置名&gt;</td></tr>
 *   <tr><td>mcp.server.failures</td><td>mcp_server_failures</td><td>gauge</td><td>server=&lt;配置名&gt;</td></tr>
 *   <tr><td>mcp.server.rejected</td><td>mcp_server_rejected</td><td>gauge</td><td>server=&lt;配置名&gt;</td></tr>
 *   <tr><td>mcp.server.circuit</td><td>mcp_server_circuit</td><td>gauge</td>
 *       <td>server=&lt;配置名&gt;、state=closed|open|half_open</td></tr>
 *   <tr><td>mcp.server.calls.this.minute</td><td>mcp_server_calls_this_minute</td><td>gauge</td>
 *       <td>server=&lt;配置名&gt;</td></tr>
 *   <tr><td>mcp.server.quota</td><td>mcp_server_quota</td><td>gauge</td><td>server=&lt;配置名&gt;</td></tr>
 * </table>
 *
 * <p>既有指标不重复造轮子（见 {@code docs/guide/observability.md}）：
 * {@code agent.stage{stage}}、{@code llm.calls{result}}、{@code llm.latency}、
 * {@code structured.planning{path,result}}、{@code agent.tool.loop{result}}、
 * {@code sse.disconnects{reason}}、{@code ratelimit.failopen}。
 */
@Component
@Slf4j
public class TravelMetrics {

    /** 规划结果：success=正常产出计划，degraded=规划完成但本轮发生阶段预算降级，failed=规划抛异常 */
    public static final String PLAN_OUTCOME = "travel.plan.outcome";
    /** 确认提交结果：success=已提交，conflict=并发/指纹/版本冲突，failed=方案不可用/风险门拒绝/提交异常 */
    public static final String CONFIRM_OUTCOME = "travel.confirm.outcome";
    /** RAG 检索结果：hit=命中非空，miss=空结果（不确定「知识库没资料」还是「索引坏了」，需与检索耗时一起看） */
    public static final String RAG_RETRIEVAL = "travel.rag.retrieval";
    /** LLM token 计数：type=prompt|completion（来自响应 usage，缺失时不计，不估算） */
    public static final String LLM_TOKENS = "travel.llm.tokens";
    /** LLM 成本估算（美元累加）：单价未配置时完全不记录，避免用 0 或过期价目表虚报 */
    public static final String LLM_COST_USD = "travel.llm.cost.usd";
    /**
     * 单轮花费预算结果（每轮 Agent 调用 +1）：result=within / exhausted。
     * exhausted 时用 limit 指出卡在哪一项——这是「预算是不是设得太紧」的唯一低基数依据。
     */
    public static final String AGENT_SPEND = "travel.agent.spend";
    /**
     * 阶段三可靠性指标（P0）：Outbox 待办按状态计数（Gauge，tag=status:pending|dispatching|done|dead）。
     * DEAD 是「同步工作已死、需要人工介入」的唯一低基数信号；积压看 pending 的绝对值。
     */
    public static final String OUTBOX_STATUS = "outbox.status";
    /** 被恢复任务判为 ABANDONED 的 run 数（Counter，无 tag）：结果未知的运行必须能被计数，而不是只留在日志 */
    public static final String AGENT_RUN_ABANDONED = "agent.run.abandoned";
    /** 过期确认租约被清扫回 PENDING 的次数（Counter，无 tag）：持续非 0 说明确认链路在崩溃/超时 */
    public static final String PENDING_PLAN_SWEPT = "pending.plan.swept";

    /**
     * MCP 总开关（Gauge，1=启用 / 0=未启用）：由 {@code travel.mcp.enabled} 直接决定。
     * 这是唯一「0 就是真实答案」的 MCP 指标——开关状态在配置期已知，不是观测不到。
     */
    public static final String MCP_ENABLED = "mcp.server.enabled";
    /**
     * 单个 MCP server 是否接入成功（Gauge，tag=server）：1=已接入并注册工具，0=连接/握手失败
     * （失败原因在 {@code /api/system/status} 的 {@code mcpLastError}）。
     * 「启用了但某个 server 没接上」此前只能靠翻启动日志，现在是一条可告警的序列。
     */
    public static final String MCP_SERVER_CONNECTED = "mcp.server.connected";
    /** 单个 MCP server 已注册进 ToolRegistry 的工具数（Gauge，tag=server）：按 allow-tools 过滤后的结果 */
    public static final String MCP_SERVER_TOOLS = "mcp.server.tools";
    /**
     * 单个 MCP server 被放行并执行的调用总数（Gauge，tag=server，单调递增；失败/超时的尝试也计入，
     * 因为它们同样烧掉上游配额）。判读速率用 {@code rate(mcp_server_calls[10m])}。
     */
    public static final String MCP_SERVER_CALLS = "mcp.server.calls";
    /** 单个 MCP server 的调用失败总数（Gauge，tag=server，单调递增；只统计放行后的执行失败，不含被拒） */
    public static final String MCP_SERVER_FAILURES = "mcp.server.failures";
    /**
     * 单个 MCP server 被治理闸门拒绝的调用数（Gauge，tag=server，单调递增）：
     * 白名单/每分钟配额/熔断三种拒绝都算。持续增长说明「模型想用但用不上」，与 failures 分开看。
     */
    public static final String MCP_SERVER_REJECTED = "mcp.server.rejected";
    /**
     * 单个 MCP server 的当前熔断状态（Gauge，tag=server + state=closed|open|half_open，命中为 1、其余为 0）。
     * 三态而不是 0/1/2 数值：{@code mcp_server_circuit{state="open"} == 1} 可以直接写进告警，
     * 半开探测期间 open 自动变 0，恢复动作也会自动解除告警。
     */
    public static final String MCP_SERVER_CIRCUIT = "mcp.server.circuit";
    /**
     * 单个 MCP server 在当前 60 秒窗口内的已用调用数（Gauge，tag=server）。
     * 与 {@link #MCP_SERVER_CALLS} 的区别：后者是累计值，判读速率要 rate()；前者是闸门<b>正在生效</b>的那个窗口，
     * 与 {@code max-calls-per-minute} 直接比较就是「还有多少余量」。
     * <b>未配置配额时必须是 NaN</b>：{@code McpGovernor} 此时恒为 0，报 0 会被读成「这个 server 很闲」。
     */
    public static final String MCP_SERVER_CALLS_THIS_MINUTE = "mcp.server.calls.this.minute";
    /**
     * 单个 MCP server 配置的每分钟调用上限（Gauge，tag=server）：配额未配置时为 NaN。
     * 与 {@link #MCP_SERVER_CALLS_THIS_MINUTE} 同生同灭——只有两条一起存在，「用量/上限」才有意义。
     */
    public static final String MCP_SERVER_QUOTA = "mcp.server.quota";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_DEGRADED = "degraded";
    public static final String RESULT_FAILED = "failed";
    public static final String RESULT_CONFLICT = "conflict";
    public static final String RESULT_HIT = "hit";
    public static final String RESULT_MISS = "miss";

    public static final String TOKEN_PROMPT = "prompt";
    public static final String TOKEN_COMPLETION = "completion";

    /** 花费预算结果：本轮没触顶 / 触顶后按降级语义收尾 */
    public static final String SPEND_WITHIN = "within";
    public static final String SPEND_EXHAUSTED = "exhausted";
    /** 没触顶时的 limit tag 取值：保持 tag 始终存在，PromQL 不必处理缺 tag 的序列 */
    public static final String SPEND_LIMIT_NONE = "none";

    private final MeterRegistry meterRegistry;
    private final LlmProperties llmProperties;

    public TravelMetrics(MeterRegistry meterRegistry, LlmProperties llmProperties) {
        this.meterRegistry = meterRegistry;
        this.llmProperties = llmProperties;
    }

    /** 规划结果 +1（@AgentEngine 规划完成/降级/异常处调用） */
    public void planOutcome(String result) {
        count(PLAN_OUTCOME, "result", result, 1.0);
    }

    /** 确认提交结果 +1（@PlanConfirmationService 各分支调用） */
    public void confirmOutcome(String result) {
        count(CONFIRM_OUTCOME, "result", result, 1.0);
    }

    /** RAG 检索命中/未命中 +1（@RagService.searchWithCitations） */
    public void ragRetrieval(boolean hit) {
        count(RAG_RETRIEVAL, "result", hit ? RESULT_HIT : RESULT_MISS, 1.0);
    }

    /**
     * 记录一次 LLM 调用的 token 用量与估算成本。
     *
     * <p>只有响应里确实带 {@code usage} 时才调用（缺失就跳过：宁可没有成本数据，
     * 也不能用「字符数 ÷ 4」之类猜测写进成本看板）。单价默认 0 = 不记成本。
     *
     * @param promptTokens     usage.prompt_tokens，未知传 0
     * @param completionTokens usage.completion_tokens，未知传 0
     */
    public void recordLlmUsage(long promptTokens, long completionTokens) {
        if (promptTokens > 0) {
            count(LLM_TOKENS, "type", TOKEN_PROMPT, promptTokens);
        }
        if (completionTokens > 0) {
            count(LLM_TOKENS, "type", TOKEN_COMPLETION, completionTokens);
        }
        double usd = estimateCostUsd(promptTokens, completionTokens);
        if (usd > 0) {
            increment(LLM_COST_USD, usd);
        }
    }

    /** 按可配置单价估算成本；单价未配置（默认 0）返回 0，调用方据此跳过成本指标 */
    public double estimateCostUsd(long promptTokens, long completionTokens) {
        try {
            double promptPrice = llmProperties.getPromptPricePer1kUsd();
            double completionPrice = llmProperties.getCompletionPricePer1kUsd();
            if (promptPrice <= 0 && completionPrice <= 0) {
                return 0d;
            }
            return Math.max(0, promptTokens) / 1000d * Math.max(0d, promptPrice)
                    + Math.max(0, completionTokens) / 1000d * Math.max(0d, completionPrice);
        } catch (RuntimeException e) {
            log.debug("估算 LLM 成本失败: {}", e.getMessage());
            return 0d;
        }
    }

    /**
     * 记一轮 Agent 调用的花费结果（第二阶段）：没触顶记 within，触顶记 exhausted 并带上触顶项。
     *
     * <p>为什么只记「结果」而不把用了多少 token/多少钱当 tag：那是无界数值（每秒都在变），
     * 进 tag 就是基数炸弹。用量本身由 {@code travel.llm.tokens} / {@code travel.llm.cost.usd}
     * 解释，这里只回答「有多少轮被预算截断、卡在哪一项」。
     */
    public void spendOutcome(AgentSpend.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String result = snapshot.exhausted() == null ? SPEND_WITHIN : SPEND_EXHAUSTED;
        String limit = snapshot.exhausted() == null ? SPEND_LIMIT_NONE : snapshot.exhausted().tag();
        count(AGENT_SPEND, "result", result, "limit", limit, 1.0);
    }

    /** 被恢复任务判为 ABANDONED 的运行 +N（结果未知的运行必须可计数；N 为 0 时不创建序列） */
    public void agentRunAbandoned(int count) {
        if (count > 0) {
            increment(AGENT_RUN_ABANDONED, count);
        }
    }

    /** 过期确认租约被清扫回 PENDING +N（N 为 0 时不创建序列） */
    public void pendingPlanSwept(int count) {
        if (count > 0) {
            increment(PENDING_PLAN_SWEPT, count);
        }
    }

    private void count(String name, String tagKey, String tagValue, double amount) {
        if (amount <= 0 || tagValue == null) {
            return;
        }
        try {
            Counter counter = meterRegistry.counter(name, tagKey, tagValue);
            counter.increment(amount);
        } catch (RuntimeException e) {
            // 指标故障绝不影响主流程
            log.debug("记录指标失败: name={} {}={} err={}", name, tagKey, tagValue, e.getMessage());
        }
    }

    /** 双 tag 计数：两个 tag 都是枚举型（result/limit），基数受控 */
    private void count(String name, String tagKey1, String tagValue1, String tagKey2, String tagValue2,
                       double amount) {
        if (amount <= 0 || tagValue1 == null || tagValue2 == null) {
            return;
        }
        try {
            meterRegistry.counter(name, tagKey1, tagValue1, tagKey2, tagValue2).increment(amount);
        } catch (RuntimeException e) {
            log.debug("记录指标失败: name={} {}={} {}={} err={}",
                    name, tagKey1, tagValue1, tagKey2, tagValue2, e.getMessage());
        }
    }

    private void increment(String name, double amount) {
        try {
            meterRegistry.counter(name).increment(amount);
        } catch (RuntimeException e) {
            log.debug("记录指标失败: name={} err={}", name, e.getMessage());
        }
    }
}