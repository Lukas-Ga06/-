package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.llm.LlmUsage;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.SafetyInterceptor;
import com.travel.agent.tool.ToolInvoker;
import com.travel.agent.tool.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 第二阶段：研究循环（research 阶段）的花费门禁。
 *
 * <p>验证的是「时间还很充裕，但钱已经花够」时循环的行为——这正是时间预算覆盖不到的场景：
 * <ul>
 *   <li>工具次数触顶：不再执行工具，按 partial 收尾保留已查到的事实；</li>
 *   <li>LLM 次数触顶：不再发起决策调用，同样 partial 收尾；</li>
 *   <li>正常路径：工具与 LLM 的用量真实记进 {@link AgentSpend}（不记账就等于没预算）。</li>
 * </ul>
 */
class AgentToolLoopSpendTest {

    private static final Set<String> ALLOWED = Set.of("echo_tool");
    private static final String FINAL = "{\"action\":\"FINAL\",\"summary\":\"够了\"}";

    /** 按顺序吐决策的客户端替身（不同参数避免命中「重复调用去重」） */
    private static final class ScriptedLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        private final ArrayDeque<String> responses;

        ScriptedLlm(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls.incrementAndGet();
            return responses.isEmpty() ? FINAL : responses.poll();
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
            return chat(messages, temperature);
        }

        @Override
        public LlmUsage takeLastUsage() {
            // 供应商用量由真实实现解析；这里固定返回 NONE（没有 usage 就不该记 token）
            return LlmUsage.NONE;
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

    private static String callTool(String city) {
        return "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"city\":\"" + city
                + "\"},\"reason\":\"查一下\"}";
    }

    private AgentToolLoop loop(LlmClient llm) {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));
        return new AgentToolLoop(llm, registry,
                new ToolInvoker(registry, new SafetyInterceptor(),
                        mock(com.travel.agent.service.AgentTraceService.class)),
                new ObjectMapper(), new SimpleMeterRegistry());
    }

    private AgentContext context(AgentSpend spend) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage("研究");
        // 时间给足 60s：本组用例要证明「即使时间还有，花费到顶也会停手」
        ctx.setBudget(AgentBudget.start(60_000L, 30_000, spend));
        return ctx;
    }

    @Test
    @DisplayName("工具次数上限 0：第一次要调工具就停手，不执行工具也不假装研究完成")
    void toolCapStopsBeforeFirstToolCall() {
        AgentSpend spend = spend(20, 0);
        AgentContext ctx = context(spend);
        ScriptedLlm llm = new ScriptedLlm(callTool("上海"), callTool("北京"));

        AgentToolLoop.LoopResult result = loop(llm).run(ctx, "收集上海事实", ALLOWED, 4);

        assertThat(result.completed()).isFalse();
        assertThat(result.partial()).isFalse();
        assertThat(result.summary()).contains("工具调用花费预算耗尽");
        assertThat(result.steps()).isEmpty();
        assertThat(spend.snapshot().toolCalls()).as("一次都没真的执行").isZero();
        assertThat(ctx.getBudgetDegradations()).singleElement().satisfies(d -> {
            assertThat(d.stage()).isEqualTo(AgentBudget.STAGE_TOOL);
            assertThat(d.detail()).contains("花费预算").contains("工具调用已用 0/0");
        });
    }

    @Test
    @DisplayName("工具次数上限 1：执行一次后停手，已查到的事实按 partial 保留给下游")
    void toolCapKeepsCollectedFacts() {
        AgentSpend spend = spend(20, 1);
        AgentContext ctx = context(spend);
        ScriptedLlm llm = new ScriptedLlm(callTool("上海"), callTool("北京"), FINAL);

        AgentToolLoop.LoopResult result = loop(llm).run(ctx, "收集上海事实", ALLOWED, 4);

        assertThat(result.completed()).as("有一条成功观察就是部分完成，不是失败").isTrue();
        assertThat(result.partial()).isTrue();
        assertThat(result.summary()).contains("可用工具次数").contains("已收集 1 条可用事实");
        assertThat(result.notes()).hasSize(1);
        assertThat(result.steps()).hasSize(1);
        assertThat(llm.calls.get()).as("第二步只做决策、不执行工具").isEqualTo(2);
        assertThat(spend.snapshot().toolCalls()).isEqualTo(1);
        assertThat(spend.snapshot().llmCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("LLM 次数上限 1：不再发起第二次决策调用，同样按 partial 收尾")
    void llmCapStopsBeforeNextDecision() {
        AgentSpend spend = spend(1, 20);
        AgentContext ctx = context(spend);
        ScriptedLlm llm = new ScriptedLlm(callTool("上海"), callTool("北京"));

        AgentToolLoop.LoopResult result = loop(llm).run(ctx, "收集上海事实", ALLOWED, 4);

        assertThat(result.completed()).isTrue();
        assertThat(result.partial()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(llm.calls.get()).isEqualTo(1);
        assertThat(spend.snapshot().llmCalls()).isEqualTo(1);
        assertThat(ctx.getBudgetDegradations()).singleElement().satisfies(d -> {
            assertThat(d.stage()).isEqualTo(AgentBudget.STAGE_RESEARCH);
            assertThat(d.detail()).contains("花费预算").contains("LLM 调用已用 1/1");
        });
    }

    @Test
    @DisplayName("未启用花费预算：循环行为与升级前一致，计数保持为 0")
    void unlimitedSpendKeepsLegacyBehaviour() {
        AgentContext ctx = context(AgentSpend.unlimited());
        ScriptedLlm llm = new ScriptedLlm(callTool("上海"), FINAL);

        AgentToolLoop.LoopResult result = loop(llm).run(ctx, "收集上海事实", ALLOWED, 4);

        assertThat(result.completed()).isTrue();
        assertThat(result.partial()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(ctx.hasBudgetDegradations()).isFalse();
        assertThat(ctx.getBudget().spend().snapshot().llmCalls()).isZero();
        assertThat(ctx.getBudget().spend().snapshot().toolCalls()).isZero();
    }

    /** 只控制 LLM 与工具次数上限，其余不设限 */
    private static AgentSpend spend(int maxLlmCalls, int maxToolCalls) {
        return AgentSpend.of(new AgentSpend.Config(true, maxLlmCalls, maxToolCalls, -1, -1, -1, 0d, 0d));
    }
}