package com.travel.agent.agent;

import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.llm.LlmUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第二阶段：{@link AgentBudget} 与 {@link AgentSpend} 的接线（纯单测，不启 Spring 上下文）。
 *
 * <p>三件事必须在预算层就成立，否则各阶段写得再对也白搭：
 * <ul>
 *   <li>{@code budget.chat} 是唯一的记账口——调用一次就记一次 + 记真实 usage，漏不掉；</li>
 *   <li>花费触顶时<b>不发起</b>调用（返回 null 走既有降级），而不是先花了再说；</li>
 *   <li>降级留痕里能读到具体花费原因（用户看到的是「token 用满」而不是含糊的「预算不足」）。</li>
 * </ul>
 */
class AgentBudgetSpendTest {

    /**
     * 会「上报 usage」的客户端替身：真实实现从响应里解析 usage，
     * 这里在调用时把预设用量挂到 takeLastUsage 上（一次消费后清空）。
     */
    private static final class UsageLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> timeouts = new ArrayList<>();
        private final LlmUsage usage;
        private LlmUsage pending;

        UsageLlm(LlmUsage usage) {
            this.usage = usage;
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls.incrementAndGet();
            pending = usage;
            return "ok";
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
            calls.incrementAndGet();
            timeouts.add(readTimeoutMs);
            pending = usage;
            return "ok";
        }

        @Override
        public LlmUsage takeLastUsage() {
            LlmUsage taken = pending;
            pending = null;
            return taken == null ? LlmUsage.NONE : taken;
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static AgentSpend spend(int maxLlmCalls, long maxTokens, double maxCostUsd,
                                    double promptPrice, double completionPrice) {
        return AgentSpend.of(new AgentSpend.Config(true, maxLlmCalls, -1, -1, maxTokens, maxCostUsd,
                promptPrice, completionPrice));
    }

    private static AgentBudget budget(AgentSpend spend) {
        return AgentBudget.start(60_000L, 30_000, spend);
    }

    @Test
    @DisplayName("budget.chat 自动记账：每次调用 +1，并把供应商 usage 累加进 token")
    void chatRecordsCallAndUsage() {
        AgentSpend spend = spend(-1, -1, -1, 0d, 0d);
        AgentBudget budget = budget(spend);
        UsageLlm llm = new UsageLlm(new LlmUsage(120, 40));

        assertThat(budget.chat(llm, List.of(LlmMessage.user("hi")), 0.1)).isEqualTo("ok");
        assertThat(budget.chat(llm, List.of(LlmMessage.user("hi")), 0.1)).isEqualTo("ok");

        AgentSpend.Snapshot snapshot = spend.snapshot();
        assertThat(snapshot.llmCalls()).isEqualTo(2);
        assertThat(snapshot.promptTokens()).isEqualTo(240);
        assertThat(snapshot.completionTokens()).isEqualTo(80);
        assertThat(snapshot.totalTokens()).isEqualTo(320);
        assertThat(snapshot.exhausted()).isNull();
    }

    @Test
    @DisplayName("调用次数触顶：chat 直接返回 null 且不再打客户端（先花再判不可接受）")
    void llmCallCapRefusesBeforeCallingClient() {
        AgentBudget budget = budget(spend(1, -1, -1, 0d, 0d));
        UsageLlm llm = new UsageLlm(LlmUsage.NONE);

        assertThat(budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)).isTrue();
        assertThat(budget.chat(llm, List.of(LlmMessage.user("hi")), 0.1)).isEqualTo("ok");
        assertThat(llm.calls.get()).isEqualTo(1);

        assertThat(budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)).isFalse();
        assertThat(budget.chat(llm, List.of(LlmMessage.user("hi")), 0.1)).isNull();
        assertThat(llm.calls).as("触顶后的调用必须被拦在客户端之前").hasValue(1);
        assertThat(budget.spend().describeExhaustion()).isEqualTo("LLM 调用已用 1/1 次");
    }

    @Test
    @DisplayName("token 触顶：下一次调用被拒，但已有调用留下的用量与费用仍在快照里")
    void tokenCapRefusesNextCall() {
        AgentBudget budget = budget(spend(-1, 100L, -1, 0d, 0d));
        UsageLlm llm = new UsageLlm(new LlmUsage(80, 30));

        budget.chat(llm, List.of(LlmMessage.user("hi")), 0.1);
        assertThat(budget.spend().totalTokens()).isEqualTo(110);
        assertThat(budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)).isFalse();
        assertThat(budget.chat(llm, List.of(LlmMessage.user("hi")), 0.1)).isNull();
        assertThat(llm.calls).hasValue(1);
    }

    @Test
    @DisplayName("降级留痕带上花费原因：用户看到「token 用满」，不是含糊的「预算不足」")
    void degradationDetailExplainsSpendExhaustion() {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setBudget(budget(spend(-1, 100L, -1, 0d, 0d)));
        UsageLlm llm = new UsageLlm(new LlmUsage(80, 30));
        ctx.getBudget().chat(llm, List.of(LlmMessage.user("hi")), 0.1);

        ctx.recordBudgetDegradation(AgentBudget.STAGE_RESEARCH, "研究循环提前结束");

        assertThat(ctx.getBudgetDegradations()).singleElement().satisfies(degradation -> {
            assertThat(degradation.stage()).isEqualTo(AgentBudget.STAGE_RESEARCH);
            assertThat(degradation.detail())
                    .contains("研究循环提前结束")
                    .contains("花费预算")
                    .contains("token 已用 110/100");
        });
    }

    @Test
    @DisplayName("时间没耗尽但花费触顶：降级记录的 remainingMs 仍是真实剩余时间（不伪装成超时）")
    void spendExhaustionDoesNotFakeTimeExhaustion() {
        AgentContext ctx = new AgentContext();
        ctx.setBudget(budget(spend(0, -1, -1, 0d, 0d)));

        ctx.recordBudgetDegradation(AgentBudget.STAGE_TOOL, "未执行外部工具查询");

        assertThat(ctx.getBudget().isExhausted()).isFalse();
        assertThat(ctx.getBudgetDegradations()).singleElement()
                .satisfies(d -> assertThat(d.remainingMs()).isPositive());
    }

    @Test
    @DisplayName("调用方自有读超时：预算开启时取「要求值」与「剩余预算」的较小值")
    void preferredReadTimeoutIsHonouredAndCapped() {
        UsageLlm llm = new UsageLlm(LlmUsage.NONE);
        AgentBudget roomy = AgentBudget.start(60_000L, 30_000, AgentSpend.unlimited());
        roomy.chat(llm, List.of(LlmMessage.user("hi")), 0.4, 8_000);
        assertThat(llm.timeouts).singleElement().isEqualTo(8_000);

        UsageLlm tight = new UsageLlm(LlmUsage.NONE);
        AgentBudget tightBudget = AgentBudget.start(1_000L, 30_000, AgentSpend.unlimited());
        tightBudget.chat(tight, List.of(LlmMessage.user("hi")), 0.4, 8_000);
        assertThat(tight.timeouts).singleElement().satisfies(timeout ->
                assertThat(timeout).isLessThanOrEqualTo(1_000));
    }

    @Test
    @DisplayName("未启用时间预算：保持原来的调用方式（两参），润色类调用仍用自有超时")
    void unlimitedBudgetKeepsLegacyCallPaths() {
        UsageLlm twoArg = new UsageLlm(LlmUsage.NONE);
        AgentBudget.unlimited().chat(twoArg, List.of(LlmMessage.user("hi")), 0.4);
        assertThat(twoArg.timeouts).as("三参调用不能凭空出现").isEmpty();

        UsageLlm withOwnTimeout = new UsageLlm(LlmUsage.NONE);
        AgentBudget.unlimited().chat(withOwnTimeout, List.of(LlmMessage.user("hi")), 0.4, 8_000);
        assertThat(withOwnTimeout.timeouts).singleElement().isEqualTo(8_000);
    }
}