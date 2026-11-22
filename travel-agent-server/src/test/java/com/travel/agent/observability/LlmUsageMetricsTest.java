package com.travel.agent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.llm.OpenAiCompatibleLlmClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

/**
 * LLM token / 成本解析（T5）：只认响应里的 {@code usage}，缺失就跳过，绝不估算。
 *
 * <p>测试不触网：直接构造 OpenAI 兼容响应 JSON，调用客户端的私有 {@code recordUsage}。
 * 指标来自真实 {@link TravelMetrics} + SimpleMeterRegistry，验的是"解析 → 记账"整条链。
 */
class LlmUsageMetricsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmProperties props = new LlmProperties();

    private OpenAiCompatibleLlmClient client() {
        return new OpenAiCompatibleLlmClient(props, registry, new TravelMetrics(registry, props));
    }

    private void record(String json) throws Exception {
        JsonNode response = objectMapper.readTree(json);
        OpenAiCompatibleLlmClient client = client();
        ReflectionTestUtils.invokeMethod(client, "recordUsage", response);
    }

    private double tokens(String type) {
        return registry.get(TravelMetrics.LLM_TOKENS).tag("type", type).counter().count();
    }

    @Test
    @DisplayName("标准 OpenAI 响应：prompt/completion token 分别记账，成本按单价估算")
    void parsesOpenAiUsage() throws Exception {
        props.setPromptPricePer1kUsd(0.002);
        props.setCompletionPricePer1kUsd(0.004);

        record("""
                {"choices":[{"message":{"content":"上海三日游"}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500}}
                """);

        assertThat(tokens("prompt")).isEqualTo(1200.0);
        assertThat(tokens("completion")).isEqualTo(300.0);
        // 1200/1000*0.002 + 300/1000*0.004 = 0.0036 USD
        assertThat(registry.get(TravelMetrics.LLM_COST_USD).counter().count())
                .isCloseTo(0.0036, within(1e-9));
    }

    @Test
    @DisplayName("没有 usage（部分兼容实现/Ollama）：不创建指标，不猜测 token 数")
    void missingUsageCreatesNoMeters() throws Exception {
        record("""
                {"choices":[{"message":{"content":"ok"}}]}
                """);

        assertThat(registry.find(TravelMetrics.LLM_TOKENS).counters()).isEmpty();
        assertThat(registry.find(TravelMetrics.LLM_COST_USD).counter()).isNull();
    }

    @Test
    @DisplayName("input_tokens/output_tokens 兼容写法也能解析")
    void supportsInputOutputTokenNames() throws Exception {
        record("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"input_tokens":10,"output_tokens":20}}
                """);

        assertThat(tokens("prompt")).isEqualTo(10.0);
        assertThat(tokens("completion")).isEqualTo(20.0);
    }

    @Test
    @DisplayName("usage 为 null 或字段非法：静默跳过，不影响调用方")
    void malformedUsageNeverThrows() {
        assertThatCode(() -> record("""
                {"choices":[{"message":{"content":"ok"}}],"usage":null}
                """)).doesNotThrowAnyException();
        assertThatCode(() -> record("""
                {"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":"n/a"}}
                """)).doesNotThrowAnyException();

        assertThat(registry.find(TravelMetrics.LLM_TOKENS).counters()).isEmpty();
    }

    @Test
    @DisplayName("单价未配置（默认 0）：只记 token，不写成本（避免虚报 0 成本）")
    void noCostWhenPriceNotConfigured() throws Exception {
        record("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":100}}
                """);

        assertThat(tokens("prompt")).isEqualTo(100.0);
        assertThat(registry.find(TravelMetrics.LLM_COST_USD).counter()).isNull();
    }
}