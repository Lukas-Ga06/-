package com.travel.agent.resilience;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.travel.agent.llm.DelegatingLlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.llm.LlmRuntimeStatus;
import com.travel.agent.llm.OpenAiCompatibleLlmClient;
import com.travel.agent.observability.TravelMetrics;
import com.travel.agent.service.LlmQuotaService;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容 LLM 的降级矩阵（T3）：200 / 429 / 5xx / 坏 JSON / 空内容 / 读超时 / 恢复，
 * 以及 {@link DelegatingLlmClient} 在「不可用」时把 null 交回上层（Agent 用规则模板兜底）。
 *
 * <p>只复用已有可配置项（{@code travel.llm.base-url} / connect / read timeout），
 * 不改 token/成本埋点（T5 负责）：本测试注入的 {@link TravelMetrics} 是 mock。
 *
 * <p>降级契约：LLM 失败一律返回 {@code null}，绝不向调用方抛异常（Agent/Planner 据此走模板）。
 */
class LlmResilienceTest {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String CONTENT_OK = """
            {"choices":[{"message":{"role":"assistant","content":"杭州三日游可以这样安排"}}],
             "usage":{"prompt_tokens":12,"completion_tokens":8}}
            """;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private WireMockServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private LlmProperties props(int readTimeoutMs) {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
        }
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://127.0.0.1:" + server.port() + "/v1");
        props.setApiKey("test-key");
        props.setModel("test-model");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(readTimeoutMs);
        return props;
    }

    private OpenAiCompatibleLlmClient client(LlmProperties props) {
        return new OpenAiCompatibleLlmClient(props, registry, mock(TravelMetrics.class));
    }

    private static final List<LlmMessage> MESSAGES = List.of(LlmMessage.user("帮我规划杭州三日游"));

    private void stubOk() {
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(CONTENT_OK)));
    }

    @Test
    @DisplayName("200：返回内容，不记降级")
    void successReturnsContent() {
        OpenAiCompatibleLlmClient client = client(props(1000));
        stubOk();

        assertThat(client.chat(MESSAGES, 0.4)).isEqualTo("杭州三日游可以这样安排");
        DegradedMetrics.assertNeverDegraded(registry, ExternalDegradationMetrics.PROVIDER_LLM);
    }

    @Test
    @DisplayName("429：返回 null 不抛异常，reason=rate_limited")
    void rateLimitedDegradesToNull() {
        OpenAiCompatibleLlmClient client = client(props(1000));
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(429)));

        assertThat(client.chat(MESSAGES, 0.4)).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_LLM, ExternalDegradationMetrics.REASON_RATE_LIMITED);
    }

    @Test
    @DisplayName("500：返回 null 不抛异常，reason=server_error")
    void serverErrorDegradesToNull() {
        OpenAiCompatibleLlmClient client = client(props(1000));
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(client.chat(MESSAGES, 0.4)).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_LLM, ExternalDegradationMetrics.REASON_SERVER_ERROR);
    }

    @Test
    @DisplayName("坏 JSON：返回 null 不抛异常，reason=bad_response")
    void malformedJsonDegradesToNull() {
        OpenAiCompatibleLlmClient client = client(props(1000));
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[")));

        assertThat(client.chat(MESSAGES, 0.4)).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_LLM, ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }

    @Test
    @DisplayName("空内容（choices 缺失/内容空白）：按坏响应降级")
    void emptyChoicesDegradesToNull() {
        OpenAiCompatibleLlmClient client = client(props(1000));
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"content\":\"   \"}}]}")));

        assertThat(client.chat(MESSAGES, 0.4)).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_LLM, ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }

    @Test
    @DisplayName("读超时：服务端延迟 1500ms > readTimeout 300ms，返回 null，reason=timeout")
    void readTimeoutDegradesToNull() {
        OpenAiCompatibleLlmClient client = client(props(300));
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(1500)
                .withBody(CONTENT_OK)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThat(client.chat(MESSAGES, 0.4)).isNull());
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_LLM, ExternalDegradationMetrics.REASON_TIMEOUT);
    }

    @Test
    @DisplayName("故障后恢复：第一次 500 返回 null，第二次 200 返回内容")
    void recoversAfterTransientFailure() {
        OpenAiCompatibleLlmClient client = client(props(1000));
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(500)));
        assertThat(client.chat(MESSAGES, 0.4)).isNull();

        server.resetAll();
        stubOk();
        assertThat(client.chat(MESSAGES, 0.4)).isEqualTo("杭州三日游可以这样安排");
        assertThat(DegradedMetrics.count(registry, ExternalDegradationMetrics.PROVIDER_LLM,
                ExternalDegradationMetrics.REASON_SERVER_ERROR)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("真实调用失败时 DelegatingLlmClient 返回 null（上层据此走规则模板），并记录 llm.calls=failed")
    void delegatingReturnsNullSoCallerCanFallBackToRules() {
        LlmProperties props = props(1000);
        server.stubFor(post(urlPathEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(500)));
        OpenAiCompatibleLlmClient openAi = client(props);
        LlmQuotaService quota = mock(LlmQuotaService.class);
        when(quota.tryAcquire(any())).thenReturn(true);

        DelegatingLlmClient delegating = new DelegatingLlmClient(openAi, props, quota, registry, new LlmRuntimeStatus());

        assertThat(delegating.chat(MESSAGES, 0.4)).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_LLM, ExternalDegradationMetrics.REASON_SERVER_ERROR);
        assertThat(counter("llm.calls", "result", "failed")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("未配置/未启用时 DelegatingLlmClient 不发任何请求，直接返回 null 并记 skipped")
    void unusablePropsNeverCallsRemote() {
        LlmProperties props = props(1000);
        props.setEnabled(false);
        OpenAiCompatibleLlmClient openAi = client(props);
        DelegatingLlmClient delegating = new DelegatingLlmClient(openAi, props, mock(LlmQuotaService.class),
                registry, new LlmRuntimeStatus());

        assertThat(delegating.chat(MESSAGES, 0.4)).isNull();
        assertThat(delegating.available()).isFalse();
        server.verify(0, postRequestedFor(urlPathEqualTo(CHAT_PATH)));
        assertThat(counter("llm.calls", "result", "skipped")).isEqualTo(1.0);
        DegradedMetrics.assertNeverDegraded(registry, ExternalDegradationMetrics.PROVIDER_LLM);
    }

    private double counter(String name, String tagKey, String tagValue) {
        Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }
}