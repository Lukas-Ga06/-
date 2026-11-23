package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.SafetyInterceptor;
import com.travel.agent.tool.ToolInvoker;
import com.travel.agent.tool.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P1：研究循环（research 阶段）的时间预算门禁。
 *
 * <p>用可控的慢 LLM 替身（每次调用 ~300ms）+ 小预算，验证两件事：
 * 预算耗尽后循环<b>不再发起下一步</b>（总耗时被预算封住，而不是 4 步 × 300ms），
 * 且降级可观测（LoopResult 说明 + 上下文留痕）。同时回归「预算关闭 = 行为不变」。
 */
class AgentToolLoopBudgetTest {

    private static final Set<String> ALLOWED = Set.of("echo_tool");
    private static final String CALL_TOOL =
            "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"city\":\"上海\"},\"reason\":\"查一下\"}";
    private static final String FINAL = "{\"action\":\"FINAL\",\"summary\":\"够了\"}";

    /**
     * 慢 LLM 替身：三参调用按「读超时」截断自己的等待（真实 OpenAI 兼容客户端的行为），
     * 这样预算把读超时压到 150ms 时，它就在 150ms 返回，而不是硬睡 300ms。
     */
    private static final class SlowLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> timeouts = new ArrayList<>();
        private final ArrayDeque<String> responses = new ArrayDeque<>(List.of(CALL_TOOL, FINAL));
        private final long sleepMs;

        SlowLlm(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls.incrementAndGet();
            sleep(sleepMs);
            return responses.isEmpty() ? FINAL : responses.poll();
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
            calls.incrementAndGet();
            timeouts.add(readTimeoutMs);
            sleep(Math.min(sleepMs, readTimeoutMs));
            return responses.isEmpty() ? FINAL : responses.poll();
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final class EchoTool implements AgentTool {
        @Override
        public String getName() {
            return "echo_tool";
        }

        @Override
        public String getDescription() {
            return "回显参数的测试工具";
        }

        @Override
        public RiskLevel getRiskLevel() {
            return RiskLevel.LOW;
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return Map.of("echo", args);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private AgentToolLoop loop(LlmClient llm) {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));
        return new AgentToolLoop(llm, registry,
                new ToolInvoker(registry, new SafetyInterceptor(),
                        mock(com.travel.agent.service.AgentTraceService.class)),
                new ObjectMapper(), new SimpleMeterRegistry());
    }

    private AgentContext context(AgentBudget budget) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage("研究");
        ctx.setBudget(budget);
        return ctx;
    }

    @Test
    @DisplayName("预算 150ms + 慢 LLM 300ms：只跑一步就停下，总耗时被预算封住，降级可观测")
    void budgetStopsLoopBeforeNextStep() {
        SlowLlm llm = new SlowLlm(300L);
        // 先建好循环（Mockito 桩/工具注册都是冷启动开销），再开预算窗口：
        // 否则被测代码还没跑，预算就被测试自身的初始化吃掉了
        AgentToolLoop loop = loop(llm);
        AgentContext ctx = context(AgentBudget.start(150L, 30_000));

        long started = System.nanoTime();
        AgentToolLoop.LoopResult result = loop.run(ctx, "收集上海事实", ALLOWED, 4);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertThat(result.completed())
                .as("P0-B：已有成功观察时按部分完成收尾，不再记成研究失败").isTrue();
        assertThat(result.partial()).isTrue();
        assertThat(result.summary()).as("必须如实说明是时间上限").contains("时间上限").contains("已收集 1 条可用事实");
        assertThat(result.notes()).as("已查到的事实保留给下游").hasSize(1);
        assertThat(result.steps()).as("预算只够走完第一步").hasSize(1);
        assertThat(llm.calls.get()).as("第二步不应再发起 LLM 调用").isEqualTo(1);
        assertThat(llm.timeouts).singleElement().satisfies(timeout ->
                assertThat(timeout).as("读超时被压到剩余预算内").isLessThanOrEqualTo(150));
        // 预算 150ms + 余量：绝不能出现「4 步 × 300ms = 1.2s」的旧行为
        assertThat(elapsedMs).isBetween(100L, 800L);
        assertThat(ctx.getBudgetDegradations())
                .extracting(AgentContext.BudgetDegradation::stage)
                .containsExactly(AgentBudget.STAGE_RESEARCH);
    }

    @Test
    @DisplayName("预算关闭（unlimited）：循环照旧走完并完成，不产生任何降级")
    void unlimitedBudgetKeepsLegacyLoopBehaviour() {
        SlowLlm llm = new SlowLlm(10L);
        AgentContext ctx = context(AgentBudget.unlimited());

        AgentToolLoop.LoopResult result = loop(llm).run(ctx, "收集上海事实", ALLOWED, 4);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(llm.calls.get()).isEqualTo(2);
        assertThat(ctx.hasBudgetDegradations()).isFalse();
    }
}
