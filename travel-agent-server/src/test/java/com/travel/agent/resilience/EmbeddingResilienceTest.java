package com.travel.agent.resilience;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.travel.agent.rag.EmbeddingProperties;
import com.travel.agent.rag.LocalHashEmbeddingService;
import com.travel.agent.rag.OpenAiCompatibleEmbeddingService;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 真实 Embedding 的降级矩阵（T3）：200 / 429 / 5xx / 坏 JSON / 空 data / 读超时 → 本地哈希降级。
 *
 * <p>本依赖的降级语义与其他四个不同，必须按现有实现断言（不虚构「自动恢复」）：
 * 首次失败即 <b>进程内永久降级</b>（{@code degraded} 锁存），因为混用两套向量空间会让余弦相似度全部归零。
 * 因此「故障后恢复」在这里的正确表述是：<b>服务端恢复后，新实例可用；本实例需重启才能重新用真实模型</b>。
 * 本测试同时验证这两点（latch + 新实例恢复）。
 */
class EmbeddingResilienceTest {

    private static final String EMBED_PATH = "/v1/embeddings";
    private static final String VECTOR_OK = "{\"data\":[{\"embedding\":[3.0,4.0]}]}";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LocalHashEmbeddingService fallback = new LocalHashEmbeddingService();
    private WireMockServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private EmbeddingProperties props(int readTimeoutMs) {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
        }
        EmbeddingProperties props = new EmbeddingProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://127.0.0.1:" + server.port() + "/v1");
        props.setApiKey("test-key");
        props.setModel("test-embedding");
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(readTimeoutMs);
        return props;
    }

    private OpenAiCompatibleEmbeddingService service(EmbeddingProperties props) {
        return new OpenAiCompatibleEmbeddingService(props, fallback, registry);
    }

    private void stubOk() {
        server.stubFor(post(urlPathEqualTo(EMBED_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(VECTOR_OK)));
    }

    /** 降级结果必须与本地哈希的实现完全一致（证明是「换实现」而不是「返回零向量」） */
    private void assertLocalHash(String text, float[] actual) {
        assertThat(actual).hasSize(LocalHashEmbeddingService.DIM);
        assertThat(actual).containsExactly(fallback.embed(text));
    }

    @Test
    @DisplayName("200：返回 L2 归一化的真实向量，不记降级")
    void successReturnsNormalizedVector() {
        OpenAiCompatibleEmbeddingService service = service(props(1000));
        stubOk();

        float[] vector = service.embed("故宫攻略");

        assertThat(vector).hasSize(2);
        assertThat(vector[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(vector[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(service.dimension()).isEqualTo(2);
        DegradedMetrics.assertNeverDegraded(registry, ExternalDegradationMetrics.PROVIDER_EMBEDDING);
    }

    @Test
    @DisplayName("429：降级到本地哈希，reason=rate_limited")
    void rateLimitedFallsBackToLocalHash() {
        OpenAiCompatibleEmbeddingService service = service(props(1000));
        server.stubFor(post(urlPathEqualTo(EMBED_PATH)).willReturn(aResponse().withStatus(429)));

        assertLocalHash("故宫攻略", service.embed("故宫攻略"));
        assertThat(service.dimension()).isEqualTo(LocalHashEmbeddingService.DIM);
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_EMBEDDING, ExternalDegradationMetrics.REASON_RATE_LIMITED);
    }

    @Test
    @DisplayName("500：降级到本地哈希，reason=server_error")
    void serverErrorFallsBackToLocalHash() {
        OpenAiCompatibleEmbeddingService service = service(props(1000));
        server.stubFor(post(urlPathEqualTo(EMBED_PATH)).willReturn(aResponse().withStatus(500)));

        assertLocalHash("故宫攻略", service.embed("故宫攻略"));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_EMBEDDING, ExternalDegradationMetrics.REASON_SERVER_ERROR);
    }

    @Test
    @DisplayName("坏 JSON：降级到本地哈希，reason=bad_response")
    void malformedJsonFallsBackToLocalHash() {
        OpenAiCompatibleEmbeddingService service = service(props(1000));
        server.stubFor(post(urlPathEqualTo(EMBED_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[")));

        assertLocalHash("故宫攻略", service.embed("故宫攻略"));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_EMBEDDING, ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }

    @Test
    @DisplayName("空 data（200 但无向量）：按坏响应降级到本地哈希")
    void emptyVectorFallsBackToLocalHash() {
        OpenAiCompatibleEmbeddingService service = service(props(1000));
        server.stubFor(post(urlPathEqualTo(EMBED_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[]}")));

        assertLocalHash("故宫攻略", service.embed("故宫攻略"));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_EMBEDDING, ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }

    @Test
    @DisplayName("读超时：降级到本地哈希，reason=timeout")
    void readTimeoutFallsBackToLocalHash() {
        OpenAiCompatibleEmbeddingService service = service(props(300));
        server.stubFor(post(urlPathEqualTo(EMBED_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(1500)
                .withBody(VECTOR_OK)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertLocalHash("故宫攻略", service.embed("故宫攻略")));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_EMBEDDING, ExternalDegradationMetrics.REASON_TIMEOUT);
    }

    @Test
    @DisplayName("恢复语义：降级锁存（同一实例不再打远端）；服务端恢复后新实例仍可用真实模型")
    void degradationLatchesButFreshInstanceRecovers() {
        EmbeddingProperties props = props(1000);
        OpenAiCompatibleEmbeddingService first = service(props);
        server.stubFor(post(urlPathEqualTo(EMBED_PATH)).willReturn(aResponse().withStatus(500)));
        assertLocalHash("故宫攻略", first.embed("故宫攻略"));
        server.verify(1, postRequestedFor(urlPathEqualTo(EMBED_PATH)));

        // 服务端已恢复：同一实例仍然走本地哈希，且不再发请求（锁存，避免向量空间漂移）
        server.resetAll();
        stubOk();
        assertLocalHash("再试一次", first.embed("再试一次"));
        server.verify(0, postRequestedFor(urlPathEqualTo(EMBED_PATH)));

        // 恢复真实模型的路径是重启进程：新实例（同一配置、同一 WireMock）重新用远端向量
        OpenAiCompatibleEmbeddingService restarted = service(props);
        float[] vector = restarted.embed("故宫攻略");
        assertThat(vector).hasSize(2);
        assertThat(restarted.dimension()).isEqualTo(2);
    }
}