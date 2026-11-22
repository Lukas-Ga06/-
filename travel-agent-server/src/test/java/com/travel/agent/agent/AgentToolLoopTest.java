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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.2 FR-03：Agent 自主工具调用循环的单元测试（不启 Spring 上下文）。
 *
 * <p>覆盖设计文档 5.3 的防护要求：步骤预算、同参去重、白名单外工具的处理、
 * 非法输出自纠、协作式取消、工具失败不中断循环、LLM 不可用时降级。
 */
class AgentToolLoopTest {

    /** 脚本化 LLM：按顺序吐出决策 JSON */
    private static final class ScriptedLlm implements LlmClient {
        private final ArrayDeque<String> responses;
        int calls = 0;

        ScriptedLlm(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls++;
            return responses.isEmpty() ? "{\"action\":\"FINAL\",\"summary\":\"耗尽\"}" : responses.poll();
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    /** 不可用的 LLM（模拟无 Key / 超配额） */
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

    /** 只读回显工具：返回参数本身；可配置成抛异常以测失败观察 */
    private static final class EchoTool implements AgentTool {
        private final boolean fail;

        EchoTool(boolean fail) {
            this.fail = fail;
        }

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
            if (fail) {
                throw new IllegalStateException("测试工具故障");
            }
            return Map.of("echo", args);
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentToolLoop loop(LlmClient llm, ToolRegistry registry) {
        // 追踪层用 Mockito 桩（单元测试不落库；AgentTraceService 内部本来就吞异常，桩只为满足构造）
        return new AgentToolLoop(llm, registry,
                new ToolInvoker(registry, new SafetyInterceptor(),
                        org.mockito.Mockito.mock(com.travel.agent.service.AgentTraceService.class)),
                objectMapper, new SimpleMeterRegistry());
    }

    private static final Set<String> ALLOWED = Set.of("echo_tool");

    private AgentContext context() {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage("研究");
        return ctx;
    }

    @Test
    @DisplayName("模型决定调用工具并观察结果，随后宣布完成")
    void callsToolThenFinal() {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"city\":\"上海\"},\"reason\":\"查一下\"}",
                "{\"action\":\"FINAL\",\"summary\":\"已收集\"}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(
                context(), "收集上海事实", ALLOWED, 4);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).success()).isTrue();
        assertThat(result.notes()).singleElement()
                .asString().contains("echo_tool").contains("上海");
        assertThat(llm.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("同工具同参数的第二次调用被去重跳过，不重复执行")
    void duplicateCallIsSkipped() {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"x\":1}}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"x\":1}}",
                "{\"action\":\"FINAL\",\"summary\":\"够了\"}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(
                context(), "去重验证", ALLOWED, 5);

        assertThat(result.completed()).isTrue();
        // 第二次调用被记录为“未成功执行的步骤”（去重跳过）
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).success()).isFalse();
        // notes 只包含真实执行过的观察
        assertThat(result.notes()).hasSize(1);
    }

    @Test
    @DisplayName("白名单外的工具名不执行，转为观察让模型自纠")
    void unknownToolBecomesObservation() {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"not_in_whitelist\",\"args\":{}}",
                "{\"action\":\"FINAL\",\"summary\":\"算了\"}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(
                context(), "白名单验证", ALLOWED, 4);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).isEmpty();
    }

    @Test
    @DisplayName("步骤预算耗尽时停止并返回未完成（调用方走既有降级路径）")
    void budgetExhaustionStopsLoop() {
        // 每步换参数避开去重 → 永远 CALL_TOOL → 耗尽 3 步预算
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"i\":1}}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"i\":2}}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"i\":3}}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"i\":4}}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(
                context(), "预算验证", ALLOWED, 3);

        assertThat(result.completed()).isFalse();
        assertThat(result.summary()).contains("预算耗尽");
        assertThat(result.steps()).hasSize(3);
    }

    @Test
    @DisplayName("非法 JSON 决策不致命：作为观察反馈，模型可继续输出正确动作")
    void invalidDecisionIsSelfCorrected() {
        ScriptedLlm llm = new ScriptedLlm(
                "我觉得应该先看看天气再说",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"ok\":true}}",
                "{\"action\":\"FINAL\",\"summary\":\"好了\"}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(
                context(), "自纠验证", ALLOWED, 5);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).hasSize(1);
    }

    @Test
    @DisplayName("未知动作不执行，反馈后继续")
    void unknownActionIsRejected() {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"DELEGATE\",\"task\":\"交给别人\"}",
                "{\"action\":\"FINAL\",\"summary\":\"结束\"}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(
                context(), "动作协议验证", ALLOWED, 4);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).isEmpty();
    }

    @Test
    @DisplayName("工具执行抛异常时循环不中断：失败成为观察，最终仍可完成")
    void toolFailureIsObservedNotThrown() {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{}}",
                "{\"action\":\"FINAL\",\"summary\":\"工具坏了但我完成了\"}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(true))).run(
                context(), "失败观察验证", ALLOWED, 4);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).success()).isFalse();
        // 失败步骤不进 notes（不产生“事实”）
        assertThat(result.notes()).isEmpty();
    }

    @Test
    @DisplayName("步骤预算耗尽时仍保留已成功收集的事实（未完成 != 丢弃已查到的结果）")
    void budgetExhaustionKeepsCollectedFacts() {
        // 两步成功调用 + 第三步仍在 CALL_TOOL → 预算 3 步耗尽
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"city\":\"上海\"}}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"day\":1}}",
                "{\"action\":\"CALL_TOOL\",\"tool\":\"echo_tool\",\"args\":{\"day\":2}}");
        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(
                context(), "预算耗尽但已查到东西", ALLOWED, 3);

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).hasSize(3);
        // 关键：三步全部成功执行 → notes 必须完整保留，供下游 Prompt 使用
        assertThat(result.notes()).hasSize(3);
        assertThat(result.notes()).allSatisfy(note -> assertThat(note).contains("echo_tool"));
    }

    @Test
    @DisplayName("LLM 不可用（无 Key / 超配额）→ 未完成、零步骤，调用方按旧行为降级")
    void llmUnavailableDegrades() {
        AgentToolLoop.LoopResult result = loop(new UnavailableLlm(), with(new EchoTool(false)))
                .run(context(), "降级验证", ALLOWED, 4);

        assertThat(result.completed()).isFalse();
        assertThat(result.steps()).isEmpty();
    }

    @Test
    @DisplayName("客户端已断开（协作式取消）→ 循环立即停止，不发起模型调用")
    void cancelledContextStopsLoop() {
        ScriptedLlm llm = new ScriptedLlm("{\"action\":\"FINAL\",\"summary\":\"不应到达\"}");
        AgentContext ctx = context();
        ctx.cancel();

        AgentToolLoop.LoopResult result = loop(llm, with(new EchoTool(false))).run(ctx, "取消验证", ALLOWED, 4);

        assertThat(result.completed()).isFalse();
        assertThat(result.summary()).contains("断开");
        assertThat(llm.calls).isZero();
    }

    private ToolRegistry with(AgentTool tool) {
        List<AgentTool> tools = new ArrayList<>();
        tools.add(tool);
        return new ToolRegistry(tools);
    }
}
