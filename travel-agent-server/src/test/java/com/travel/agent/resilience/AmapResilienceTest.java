package com.travel.agent.resilience;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.travel.agent.config.AmapProperties;
import com.travel.agent.service.CityGeoLocator;
import com.travel.agent.util.CoordinateTransform;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 高德地理编码的降级矩阵（T3）：200 / 429 / 5xx / 坏 JSON / 业务包体 status=0 / 读超时 / 失败后恢复，
 * 以及「批量接口失败退回逐个查询」。
 *
 * <p>被测入口是 {@link CityGeoLocator}（{@code AmapGeocodeClient} 是包内协作类）：
 * 只用 {@code locateCityViaAmap} / {@code locatePois} 这两个<b>不做 Nominatim 兜底</b>的方法，
 * 因此在离线/无外网的 CI 上也不会打真实网络。
 *
 * <p>超时预算：测试用 300ms 读超时 + 服务端固定延迟 1500ms，单个超时用例控制在秒级。
 */
class AmapResilienceTest {

    private static final String GEO_PATH = "/v3/geocode/geo";
    private static final String GEO_OK = """
            {"status":"1","info":"OK","infocode":"10000","geocodes":[
              {"location":"120.153576,30.287459","province":"浙江省","city":"杭州市","district":"西湖区"}
            ]}
            """;
    private static final double GCJ_LAT = 30.287459;
    private static final double GCJ_LNG = 120.153576;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private WireMockServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** 起一个动态端口的 WireMock，并把高德 baseUrl 指向它（这正是新增 baseUrl 配置的用途） */
    private CityGeoLocator locator(int readTimeoutMs) {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        AmapProperties props = new AmapProperties();
        props.setKey("test-key");
        props.setBaseUrl("http://127.0.0.1:" + server.port());
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(readTimeoutMs);
        return new CityGeoLocator(props, registry);
    }

    private void stubSingleGeoOk() {
        server.stubFor(get(urlPathEqualTo(GEO_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(GEO_OK)));
    }

    @Test
    @DisplayName("200：返回 GCJ-02 解析后的 WGS-84 坐标，且不记降级")
    void successReturnsWgs84Coordinates() {
        CityGeoLocator locator = locator(1000);
        stubSingleGeoOk();

        double[] located = locator.locateCityViaAmap("杭州");

        assertThat(located).isNotNull();
        double[] expected = CoordinateTransform.gcj02ToWgs84(GCJ_LAT, GCJ_LNG);
        assertThat(located[0]).isCloseTo(expected[0], org.assertj.core.data.Offset.offset(1e-9));
        assertThat(located[1]).isCloseTo(expected[1], org.assertj.core.data.Offset.offset(1e-9));
        DegradedMetrics.assertNeverDegraded(registry, ExternalDegradationMetrics.PROVIDER_AMAP);
    }

    @Test
    @DisplayName("429：返回 null 不抛异常，reason=rate_limited")
    void rateLimitedDegradesToNull() {
        CityGeoLocator locator = locator(1000);
        server.stubFor(get(urlPathEqualTo(GEO_PATH)).willReturn(aResponse().withStatus(429)));

        assertThat(locator.locateCityViaAmap("杭州")).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_AMAP, ExternalDegradationMetrics.REASON_RATE_LIMITED);
    }

    @Test
    @DisplayName("500：返回 null 不抛异常，reason=server_error")
    void serverErrorDegradesToNull() {
        CityGeoLocator locator = locator(1000);
        server.stubFor(get(urlPathEqualTo(GEO_PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(locator.locateCityViaAmap("杭州")).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_AMAP, ExternalDegradationMetrics.REASON_SERVER_ERROR);
    }

    @Test
    @DisplayName("坏 JSON：返回 null 不抛异常，reason=bad_response")
    void malformedJsonDegradesToNull() {
        CityGeoLocator locator = locator(1000);
        server.stubFor(get(urlPathEqualTo(GEO_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"1\",\"geocodes\":[")));

        assertThat(locator.locateCityViaAmap("杭州")).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_AMAP, ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }

    @Test
    @DisplayName("HTTP 200 + status=0（如 INVALID_USER_KEY）：按坏响应降级，返回 null")
    void businessErrorBodyDegradesToNull() {
        CityGeoLocator locator = locator(1000);
        server.stubFor(get(urlPathEqualTo(GEO_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\",\"infocode\":\"10001\"}")));

        assertThat(locator.locateCityViaAmap("杭州")).isNull();
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_AMAP, ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }

    @Test
    @DisplayName("读超时：服务端延迟 1500ms > readTimeout 300ms，返回 null，reason=timeout")
    void readTimeoutDegradesToNull() {
        CityGeoLocator locator = locator(300);
        server.stubFor(get(urlPathEqualTo(GEO_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(1500)
                .withBody(GEO_OK)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThat(locator.locateCityViaAmap("杭州")).isNull());
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_AMAP, ExternalDegradationMetrics.REASON_TIMEOUT);
    }

    @Test
    @DisplayName("故障后恢复：第一次 500 返回 null，第二次 200 返回坐标（客户端与 URI 均可复用）")
    void recoversAfterTransientFailure() {
        CityGeoLocator locator = locator(1000);
        server.stubFor(get(urlPathEqualTo(GEO_PATH)).willReturn(aResponse().withStatus(500)));
        assertThat(locator.locateCityViaAmap("杭州")).isNull();

        server.resetAll();
        stubSingleGeoOk();
        double[] recovered = locator.locateCityViaAmap("杭州");

        assertThat(recovered).isNotNull();
        assertThat(recovered[0]).isCloseTo(CoordinateTransform.gcj02ToWgs84(GCJ_LAT, GCJ_LNG)[0],
                org.assertj.core.data.Offset.offset(1e-9));
        // 只发生过一次降级：恢复后的成功调用不得再记指标
        assertThat(DegradedMetrics.count(registry, ExternalDegradationMetrics.PROVIDER_AMAP,
                ExternalDegradationMetrics.REASON_SERVER_ERROR)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("批量失败退回逐个查询：batch 请求 500，单点请求成功，整体仍返回全部坐标")
    void batchFailureFallsBackToPerItemQueries() {
        CityGeoLocator locator = locator(1000);
        // 批量请求的 address 是 "西湖|灵隐寺"（%7C 编码）；单点请求不含分隔符
        server.stubFor(get(urlPathEqualTo(GEO_PATH))
                .atPriority(1)
                .withQueryParam("address", containing("|"))
                .willReturn(aResponse().withStatus(500)));
        server.stubFor(get(urlPathEqualTo(GEO_PATH))
                .atPriority(2)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GEO_OK)));

        Map<String, double[]> located = locator.locatePois(List.of("西湖", "灵隐寺"), "杭州");

        assertThat(located).containsOnlyKeys("西湖", "灵隐寺");
        assertThat(located.get("西湖")).hasSize(2);
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_AMAP, ExternalDegradationMetrics.REASON_SERVER_ERROR);
    }

    @Test
    @DisplayName("批量数量不对齐：不采信批量结果（bad_response），逐个补齐")
    void batchSizeMismatchFallsBackToPerItemQueries() {
        CityGeoLocator locator = locator(1000);
        server.stubFor(get(urlPathEqualTo(GEO_PATH))
                .atPriority(1)
                .withQueryParam("address", containing("|"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"1\",\"geocodes\":[{\"location\":\"120.153576,30.287459\"}]}")));
        server.stubFor(get(urlPathEqualTo(GEO_PATH))
                .atPriority(2)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(GEO_OK)));

        Map<String, double[]> located = locator.locatePois(List.of("西湖", "灵隐寺"), "杭州");

        assertThat(located).containsOnlyKeys("西湖", "灵隐寺");
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_AMAP, ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }
}