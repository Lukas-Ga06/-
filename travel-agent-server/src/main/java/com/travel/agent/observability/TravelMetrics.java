package com.travel.agent.observability;

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

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_DEGRADED = "degraded";
    public static final String RESULT_FAILED = "failed";
    public static final String RESULT_CONFLICT = "conflict";
    public static final String RESULT_HIT = "hit";
    public static final String RESULT_MISS = "miss";

    public static final String TOKEN_PROMPT = "prompt";
    public static final String TOKEN_COMPLETION = "completion";

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

    private void increment(String name, double amount) {
        try {
            meterRegistry.counter(name).increment(amount);
        } catch (RuntimeException e) {
            log.debug("记录指标失败: name={} err={}", name, e.getMessage());
        }
    }
}