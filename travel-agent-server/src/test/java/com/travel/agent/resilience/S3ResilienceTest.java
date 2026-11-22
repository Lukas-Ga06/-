package com.travel.agent.resilience;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.travel.agent.config.S3Properties;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.service.S3PhotoStore;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * S3 兼容对象存储的降级矩阵（T3）：put 成功 / 5xx / 超时 / 恢复 / head 404→false / 错误转 {@link BusinessException}。
 *
 * <p>可控性来自 {@link S3Properties#setMaxRetries}（0 = 关掉 SDK 重试，避免一个 5xx 拖三次）与
 * {@link S3Properties#setApiCallTimeoutMs}（300ms 级总超时）；两者默认都是 {@code null}，
 * 生产行为仍是 AWS SDK 默认（3 次重试、无总超时）。
 *
 * <p>错误语义（沿用既有实现，不重写）：
 * <ul>
 *   <li>{@code store}/{@code read} 的非 404 失败 → {@code BusinessException(500)}，
 *       文案「照片保存失败，请重试」/「照片读取失败，请重试」；</li>
 *   <li>{@code read} 的 404 → {@code BusinessException(404)}「照片文件不存在（可能已被清理）」；</li>
 *   <li>{@code exists} 的 404 → {@code false}（不存在是正常结果，不算降级）；其它异常继续上抛由 Controller 转 500。</li>
 * </ul>
 */
class S3ResilienceTest {

    private static final String BUCKET = "travel-photos";
    private static final String KEY_OK = "u1/abc.jpg";
    private static final String KEY_GONE = "u1/gone.jpg";
    private static final String PUT_PATH = ".*/" + KEY_OK;
    private static final String GONE_PATH = ".*/" + KEY_GONE;
    private static final String S3_ERROR_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Error><Code>InternalError</Code><Message>boom</Message></Error>
            """;
    private static final String NO_SUCH_KEY_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>
            """;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private WireMockServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private S3PhotoStore store(int apiCallTimeoutMs) {
        return store("http://127.0.0.1:" + wireMock().port(), apiCallTimeoutMs);
    }

    private S3PhotoStore store(String endpoint, int apiCallTimeoutMs) {
        S3Properties props = new S3Properties();
        props.setEndpoint(endpoint);
        props.setRegion("us-east-1");
        props.setBucket(BUCKET);
        props.setAccessKey("test-ak");
        props.setSecretKey("test-sk");
        props.setMaxRetries(0);
        props.setApiCallTimeoutMs(apiCallTimeoutMs);
        return new S3PhotoStore(props, registry);
    }

    private WireMockServer wireMock() {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
        }
        return server;
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private void stubPutOk() {
        wireMock().stubFor(put(urlPathMatching(PUT_PATH)).willReturn(aResponse().withStatus(200)));
    }

    @Test
    @DisplayName("put 成功：不抛异常、不打降级指标")
    void putSucceeds() {
        S3PhotoStore store = store(2000);
        stubPutOk();

        assertDoesNotThrow(() -> store.store(photo(), KEY_OK));
        DegradedMetrics.assertNeverDegraded(registry, ExternalDegradationMetrics.PROVIDER_S3);
    }

    @Test
    @DisplayName("put 5xx：转 BusinessException(500) + 既有文案，reason=server_error")
    void putServerErrorBecomesBusinessException() {
        S3PhotoStore store = store(2000);
        wireMock().stubFor(put(urlPathMatching(PUT_PATH)).willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/xml")
                .withBody(S3_ERROR_XML)));

        assertThatThrownBy(() -> store.store(photo(), KEY_OK))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("照片保存失败，请重试")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(500);
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_S3, ExternalDegradationMetrics.REASON_SERVER_ERROR);
    }

    @Test
    @DisplayName("put 超时：服务端延迟 1500ms > apiCallTimeout 300ms，转 BusinessException(500)，reason=timeout")
    void putTimeoutBecomesBusinessException() {
        S3PhotoStore store = store(300);
        wireMock().stubFor(put(urlPathMatching(PUT_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(1500)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> store.store(photo(), KEY_OK))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(500));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_S3, ExternalDegradationMetrics.REASON_TIMEOUT);
    }

    @Test
    @DisplayName("故障后恢复：第一次 500 抛 BusinessException，第二次 200 上传成功")
    void recoversAfterTransientFailure() {
        S3PhotoStore store = store(2000);
        wireMock().stubFor(put(urlPathMatching(PUT_PATH)).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> store.store(photo(), KEY_OK)).isInstanceOf(BusinessException.class);

        wireMock().resetAll();
        stubPutOk();
        assertDoesNotThrow(() -> store.store(photo(), KEY_OK));
        assertThat(DegradedMetrics.count(registry, ExternalDegradationMetrics.PROVIDER_S3,
                ExternalDegradationMetrics.REASON_SERVER_ERROR)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("head 404：exists 返回 false，不记降级（对象不存在是正常业务结果）")
    void headNotFoundReturnsFalse() {
        S3PhotoStore store = store(2000);
        wireMock().stubFor(head(urlPathMatching(GONE_PATH)).willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody(NO_SUCH_KEY_XML)));

        assertFalse(store.exists(KEY_GONE));
        DegradedMetrics.assertNeverDegraded(registry, ExternalDegradationMetrics.PROVIDER_S3);
    }

    @Test
    @DisplayName("head 5xx：exists 不吞异常（沿用既有语义交由上层转 500），但记 server_error")
    void headServerErrorPropagatesAndIsRecorded() {
        S3PhotoStore store = store(2000);
        wireMock().stubFor(head(urlPathMatching(GONE_PATH)).willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/xml")
                .withBody(S3_ERROR_XML)));

        assertThatThrownBy(() -> store.exists(KEY_GONE))
                .isInstanceOf(software.amazon.awssdk.awscore.exception.AwsServiceException.class)
                .satisfies(e -> assertThat(((software.amazon.awssdk.awscore.exception.AwsServiceException) e).statusCode())
                        .isEqualTo(500));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_S3, ExternalDegradationMetrics.REASON_SERVER_ERROR);
    }

    @Test
    @DisplayName("read 404：转 BusinessException(404)（既有文案）")
    void readNotFoundBecomes404() {
        S3PhotoStore store = store(2000);
        wireMock().stubFor(get(urlPathMatching(GONE_PATH)).willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/xml")
                .withBody(NO_SUCH_KEY_XML)));

        assertThatThrownBy(() -> store.read(KEY_GONE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("照片文件不存在")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("read 5xx：转 BusinessException(500)，reason=server_error")
    void readServerErrorBecomes500() {
        S3PhotoStore store = store(2000);
        wireMock().stubFor(get(urlPathMatching(GONE_PATH)).willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/xml")
                .withBody(S3_ERROR_XML)));

        assertThatThrownBy(() -> store.read(KEY_GONE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(500);
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_S3, ExternalDegradationMetrics.REASON_SERVER_ERROR);
    }

    @Test
    @DisplayName("连不上（端口 1）：put 转 BusinessException(500)，reason=connection_error")
    void connectionRefusedBecomesBusinessException() {
        S3PhotoStore store = store("http://127.0.0.1:1", 500);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> store.store(photo(), KEY_OK))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getCode())
                        .isEqualTo(500));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_S3, ExternalDegradationMetrics.REASON_CONNECTION_ERROR);
    }
}