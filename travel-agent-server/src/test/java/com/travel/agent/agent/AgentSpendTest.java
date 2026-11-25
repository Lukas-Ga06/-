package com.travel.agent.agent;

import com.travel.agent.llm.LlmUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第二阶段「统一花费预算」的基础语义（纯单测，不启 Spring 上下文）。
 *
 * <p>覆盖四件必须成立的事：
 * <ul>
 *   <li>关闭 = 零影响：不限量实例恒放行且不累加；</li>
 *   <li>五类上限各自独立生效（调用次数 / 工具次数 / 重试 / token / 费用）；</li>
 *   <li>费用上限只在配置了真实单价时才可能触发（未配价目表时不猜价）；</li>
 *   <li>触顶后能说清「哪一项、用了多少、上限多少」——用户可见文案与排障都靠它。</li>
 * </ul>
 */
class AgentSpendTest {

    /** 只打开想测的那一项，其余不设限（-1） */
    private static AgentSpend spend(int llmCalls, int toolCalls, int retries, long tokens, double cost,
                                    double promptPrice, double completionPrice) {
        return AgentSpend.of(new AgentSpend.Config(true, llmCalls, toolCalls, retries, tokens, cost,
                promptPrice, completionPrice));
    }

    @Test
    @DisplayName("不限量：所有门禁恒放行，计数一律不累加（未开花费预算的入口行为不变）")
    void unlimitedNeverBlocksAndDoesNotCount() {
        AgentSpend spend = AgentSpend.unlimited();
        assertThat(spend.isEnabled()).isFalse();
        assertThat(spend.allowsLlmCall()).isTrue();
        assertThat(spend.allowsToolCall()).isTrue();
        assertThat(spend.allowsRetry()).isTrue();

        spend.recordLlmCall();
        spend.recordUsage(new LlmUsage(100, 50));
        spend.recordToolCall();
        spend.recordRetry();

        assertThat(spend.totalTokens()).isZero();
        assertThat(spend.snapshot().llmCalls()).isZero();
        assertThat(spend.snapshot().toolCalls()).isZero();
        assertThat(spend.snapshot().retries()).isZero();
        assertThat(spend.snapshot().exhausted()).isNull();
        assertThat(spend.summary()).isEqualTo("花费预算未启用");
    }

    @Test
    @DisplayName("LLM 调用次数：到上限后拒绝，且原因写明「已用 N/上限」")
    void llmCallCapStopsFurtherCalls() {
        AgentSpend spend = spend(2, -1, -1, -1, -1, 0d, 0d);

        assertThat(spend.allowsLlmCall()).isTrue();
        spend.recordLlmCall();
        spend.recordLlmCall();
        assertThat(spend.allowsLlmCall()).as("已用 2 / 上限 2").isFalse();
        assertThat(spend.exhausted()).contains(AgentSpend.Limit.LLM_CALLS);
        assertThat(spend.describeExhaustion()).isEqualTo("LLM 调用已用 2/2 次");

        // 计数类上限是「发起前」判定，触顶后不会再多算
        assertThat(spend.snapshot().llmCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("token：按供应商 usage 的输入+输出累计，越过上限后拒绝下一次调用")
    void tokenCapTripsAfterUsage() {
        AgentSpend spend = spend(-1, -1, -1, 100L, -1, 0d, 0d);

        spend.recordUsage(new LlmUsage(60, 51));
        assertThat(spend.totalTokens()).isEqualTo(111);
        assertThat(spend.allowsLlmCall()).as("111 > 100：下一次必须被拒").isFalse();
        assertThat(spend.exhausted()).contains(AgentSpend.Limit.TOKENS);
        assertThat(spend.describeExhaustion()).isEqualTo("token 已用 111/100");
    }

    @Test
    @DisplayName("没有 usage 的调用不推进 token 计数：不估算、不猜测")
    void usageAbsentIsNotEstimated() {
        AgentSpend spend = spend(-1, -1, -1, 100L, -1, 0d, 0d);
        spend.recordUsage(LlmUsage.NONE);
        spend.recordUsage(null);
        assertThat(spend.totalTokens()).isZero();
        assertThat(spend.allowsLlmCall()).isTrue();
    }

    @Test
    @DisplayName("费用：按配置单价折算，越过费用上限即拒绝")
    void costCapUsesConfiguredPrices() {
        // 1.0 美元 / 1K prompt，2.0 美元 / 1K completion；上限 0.002 美元
        AgentSpend spend = spend(-1, -1, -1, -1, 0.002, 1.0, 2.0);

        // 1 prompt(0.001) + 1 completion(0.002) = 0.003 > 0.002
        spend.recordUsage(new LlmUsage(1, 1));
        assertThat(spend.costUsd()).isCloseTo(0.003, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(spend.allowsLlmCall()).isFalse();
        assertThat(spend.exhausted()).contains(AgentSpend.Limit.COST);
        assertThat(spend.describeExhaustion()).startsWith("估算费用已用 $0.0030");
    }

    @Test
    @DisplayName("未配价目表：费用恒为 0，费用上限不会误伤（宁可不判，也不拿猜测单价卡请求）")
    void costCapIsInertWithoutPrices() {
        AgentSpend spend = spend(-1, -1, -1, -1, 0.0001, 0d, 0d);
        spend.recordUsage(new LlmUsage(1_000_000, 1_000_000));
        assertThat(spend.costUsd()).isZero();
        assertThat(spend.allowsLlmCall()).isTrue();
        assertThat(spend.exhausted()).isEmpty();
    }

    @Test
    @DisplayName("工具次数与重试次数各自设限，互不影响 LLM 次数")
    void toolAndRetryCapsAreIndependent() {
        AgentSpend spend = spend(-1, 1, 2, -1, -1, 0d, 0d);

        spend.recordToolCall();
        assertThat(spend.allowsToolCall()).as("工具已用 1/1").isFalse();
        assertThat(spend.describeExhaustion()).isEqualTo("工具调用已用 1/1 次");
        // 工具触顶不影响 LLM 门禁（LLM 仍可用来收尾/总结）
        assertThat(spend.allowsLlmCall()).isTrue();
        // 但 exhausted() 会先报工具项：这是「卡住循环」的真实原因
        assertThat(spend.exhausted()).contains(AgentSpend.Limit.TOOL_CALLS);

        AgentSpend retryOnly = spend(1, 1, 1, -1, -1, 0d, 0d);
        retryOnly.recordRetry();
        assertThat(retryOnly.allowsRetry()).isFalse();
        assertThat(retryOnly.exhausted()).contains(AgentSpend.Limit.RETRIES);
        assertThat(retryOnly.describeExhaustion()).isEqualTo("重试已用 1/1 次");
    }

    @Test
    @DisplayName("上限 0 表示「一次都不允许」，不是「不设限」")
    void zeroMeansForbidden() {
        AgentSpend spend = spend(0, 0, 0, -1, -1, 0d, 0d);
        assertThat(spend.allowsLlmCall()).isFalse();
        assertThat(spend.allowsToolCall()).isFalse();
        assertThat(spend.allowsRetry()).isFalse();
    }

    @Test
    @DisplayName("summary：未设限的项显示 ∞，按 runId 打日志时能一眼看出边界")
    void summaryMarksUnlimitedItems() {
        AgentSpend spend = spend(3, -1, 1, -1, -1, 0d, 0d);
        spend.recordLlmCall();
        spend.recordRetry();
        assertThat(spend.summary())
                .isEqualTo("llm=1/3 tool=0/∞ retry=1/1 tokens=0/∞ cost=$0.0000/∞");
    }
}