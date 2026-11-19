package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1：intent 阶段的预算降级。
 *
 * <p>要点：预算不够时不再发起注定读超时的 LLM 意图识别，改用规则引擎；降级要留痕，
 * 并且必须与「LLM 不可用」区分——后者是既有的规则兜底，不属于预算降级。
 */
class IntentAgentBudgetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolRegistry toolRegistry = new ToolRegistry(List.of());

    /** 慢 LLM 替身：记录调用次数与传入读超时，返回一条合法的意图 JSON */
    private static final class SlowLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> timeouts = new ArrayList<>();
        private final long sleepMs;

        SlowLlm(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls.incrementAndGet();
            sleep(sleepMs);
            return INTENT_JSON;
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
            calls.incrementAndGet();
            timeouts.add(readTimeoutMs);
            // 模拟真实客户端：读超时到了就返回（这里按 min(耗时, 读超时) 等，绝不越过预算）
            sleep(Math.min(sleepMs, readTimeoutMs));
            return INTENT_JSON;
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final String INTENT_JSON = "{\"intent\":\"PLAN_TRIP\",\"target\":\"NONE\"}";

    /** LLM 不可用（无 Key / 超配额）：既有的规则兜底路径 */
    private static final class UnavailableLlm implements LlmClient {
        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            throw new IllegalStateException("不应被调用");
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private IntentAgent intentAgent(LlmClient llm) {
        return new IntentAgent(llm, objectMapper, toolRegistry);
    }

    private AgentContext context(String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage(message);
        return ctx;
    }

    @Test
    @DisplayName("预算耗尽：不发起 LLM 意图识别，退回规则引擎，并留下 intent 降级痕迹")
    void exhaustedBudgetSkipsLlmIntent() {
        SlowLlm llm = new SlowLlm(300L);
        AgentContext ctx = context("你好");
        ctx.setBudget(AgentBudget.start(0L, 30_000));

        IntentResult intent = intentAgent(llm).detect(ctx, null);

        assertThat(intent.getIntent()).isEqualTo(IntentResult.CHITCHAT);
        assertThat(llm.calls.get()).as("预算耗尽不应再发 LLM 请求").isZero();
        assertThat(ctx.getBudgetDegradations())
                .extracting(AgentContext.BudgetDegradation::stage)
                .containsExactly(AgentBudget.STAGE_INTENT);
        assertThat(ctx.getBudgetDegradations().get(0).detail()).contains("预算");
    }

    @Test
    @DisplayName("预算充足：走 LLM 意图识别，读超时被预算封顶，不产生降级")
    void sufficientBudgetUsesLlmWithCappedTimeout() {
        SlowLlm llm = new SlowLlm(50L);
        AgentContext ctx = context("帮我规划上海三日游");
        ctx.setBudget(AgentBudget.start(2000L, 30_000));

        IntentResult intent = intentAgent(llm).detect(ctx, null);

        assertThat(intent.getIntent()).isEqualTo(IntentResult.PLAN_TRIP);
        assertThat(llm.calls.get()).isEqualTo(1);
        assertThat(llm.timeouts).singleElement().satisfies(timeout -> {
            assertThat(timeout).isPositive();
            assertThat(timeout).isLessThanOrEqualTo(2000);
        });
        assertThat(ctx.hasBudgetDegradations()).isFalse();
    }

    @Test
    @DisplayName("LLM 不可用 + 预算耗尽：只算既有规则兜底，不记预算降级（两条路径必须可区分）")
    void llmUnavailableIsNotRecordedAsBudgetDegradation() {
        AgentContext ctx = context("你好");
        ctx.setBudget(AgentBudget.start(0L, 30_000));

        IntentResult intent = intentAgent(new UnavailableLlm()).detect(ctx, null);

        assertThat(intent.getIntent()).isEqualTo(IntentResult.CHITCHAT);
        assertThat(ctx.hasBudgetDegradations())
                .as("没配置模型是既有降级，不属于「预算耗尽」")
                .isFalse();
    }
}