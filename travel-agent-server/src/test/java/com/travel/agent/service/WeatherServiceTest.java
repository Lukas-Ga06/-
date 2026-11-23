package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.travel.agent.config.WeatherProperties;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.model.WeatherLookup;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 数据源「不可降级地真实」：WeatherService 只有两条路——真实预报，或「不可用 + 原因」。
 *
 * <p>钉死四件事：
 * <ol>
 *   <li>模拟天气只在 {@code travel.weather.simulation=true} 下出现，且来源如实标注；</li>
 *   <li>真实路径失败（连不上 / 5xx / 坏 JSON / 超出 16 天窗口）一律 unavailable，<b>绝不回落模拟</b>；</li>
 *   <li>失败负缓存生效：离线时同一 city+date 不会每次都等一轮连接超时；</li>
 *   <li>依赖类失败计入 {@code external.degraded{provider=weather,reason=...}}，窗口/定位这类输入边界不计入。</li>
 * </ol>
 *
 * <p>纯单测（不起 Spring）：Open-Meteo 地址由配置注入，测试用 WireMock 指向本地，离线可重复。
 */
class WeatherServiceTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    private static TravelCatalogService catalog;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private WireMockServer server;

    @BeforeAll
    static void loadCatalog() {
        TravelCatalogService service = new TravelCatalogService(new ObjectMapper(), new RouteService());
        service.load();
        catalog = service;
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    // ==================== 1. 模拟只在开关下出现 ====================

    @Test
    @DisplayName("默认不模拟；simulation=true 时返回确定性模拟数据并如实标注来源")
    void simulationOnlyWhenExplicitlyEnabled() {
        assertThat(new WeatherProperties().isSimulation()).as("产品路径默认不得模拟").isFalse();

        WeatherProperties props = new WeatherProperties();
        props.setSimulation(true);
        WeatherService service = new WeatherService(props, catalog);

        WeatherLookup lookup = service.forecast("上海", TOMORROW);

        assertThat(lookup.available()).isTrue();
        assertThat(lookup.info().source()).isEqualTo(WeatherInfo.SOURCE_SIMULATED);
        assertThat(lookup.simulation()).isTrue();
        assertThat(lookup.reason()).isNull();
        assertThat(lookup.message()).isNull();
        // 模拟是确定性数据：同一 city+date 每次都一样（离线演示可复现）
        assertThat(service.forecast("上海", TOMORROW).info()).isEqualTo(lookup.info());
        // 模拟没有发生过真实调用：自报口径不能宣称 open-meteo / verified
        assertThat(service.provider()).isEqualTo("simulated");
        assertThat(service.verified()).isFalse();
        assertThat(service.probeCount()).isZero();
    }

    // ==================== 2. 连不上 → 不可用 + 负缓存 ====================

    @Test
    @DisplayName("远端不可达 → unavailable(网络不可达)，60 秒内二次查询命中负缓存不再外呼")
    void unreachableEndpointIsUnavailableAndNegativeCached() {
        WeatherProperties props = new WeatherProperties();
        props.setForecastBaseUrl("http://127.0.0.1:1/v1/forecast");
        WeatherService service = new WeatherService(props, catalog, registry);

        WeatherLookup first = service.forecast("上海", TOMORROW);
        assertThat(first.available()).isFalse();
        assertThat(first.reason()).isEqualTo(WeatherLookup.REASON_CONNECTION_ERROR);
        assertThat(first.message()).isEqualTo("网络不可达");
        assertThat(first.info()).isNull();
        assertThat(service.probeCount()).isEqualTo(1);

        WeatherLookup second = service.forecast("上海", TOMORROW);
        assertThat(second.available()).isFalse();
        assertThat(second.reason()).isEqualTo(WeatherLookup.REASON_CONNECTION_ERROR);
        // 负缓存命中：离线时一次三日游审核查 3 次天气，不能每次都等连接超时
        assertThat(service.probeCount()).isEqualTo(1);

        assertThat(service.provider()).isEqualTo("unavailable");
        assertThat(service.verified()).isFalse();
        assertThat(service.lastError()).isEqualTo("网络不可达");
        assertThat(degraded(registry, ExternalDegradationMetrics.REASON_CONNECTION_ERROR))
                .isGreaterThanOrEqualTo(1.0);

        // 负缓存可配：设为 0 表示不缓存失败，每次都会重新尝试（而不是永久熔断）
        props.setFailureCacheSeconds(0);
        WeatherService noCache = new WeatherService(props, catalog);
        noCache.forecast("上海", TOMORROW);
        noCache.forecast("上海", TOMORROW);
        assertThat(noCache.probeCount()).isEqualTo(2);
    }

    // ==================== 3. 超出预报窗口 → 直接不可用，不发 HTTP ====================

    @Test
    @DisplayName("超出 16 天预报窗口 → outside-forecast-window，且不发起任何 HTTP 请求")
    void outsideForecastWindowDoesNotProbe() {
        WeatherProperties props = new WeatherProperties();
        // 就算配了不可达地址也不该被访问：窗口判定在发请求之前
        props.setForecastBaseUrl("http://127.0.0.1:1/v1/forecast");
        WeatherService service = new WeatherService(props, catalog, registry);

        WeatherLookup future = service.forecast("上海", LocalDate.now().plusDays(20));
        assertThat(future.available()).isFalse();
        assertThat(future.reason()).isEqualTo(WeatherLookup.REASON_OUTSIDE_WINDOW);
        assertThat(future.message()).isEqualTo("超出 16 天预报窗口");

        WeatherLookup past = service.forecast("上海", LocalDate.now().minusDays(1));
        assertThat(past.available()).isFalse();
        assertThat(past.reason()).isEqualTo(WeatherLookup.REASON_OUTSIDE_WINDOW);

        assertThat(service.probeCount()).isZero();
        // 输入边界不计入降级指标（否则天气告警会被「用户查了远期日期」淹没）
        assertThat(degradedTotal(registry)).isZero();
    }

    // ==================== 4. 正常人可读的结果 ====================

    @Test
    @DisplayName("WireMock 200 → available、字段解析正确、source=real，且不记降级")
    void successfulForecastIsParsedAndReportedVerified() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"daily\":{\"weather_code\":[61],\"temperature_2m_min\":[12.4],"
                        + "\"temperature_2m_max\":[18.6],\"precipitation_probability_max\":[72]}}")));

        WeatherService service = new WeatherService(propsForServer(), catalog, registry);
        WeatherLookup lookup = service.forecast("上海", TOMORROW);

        assertThat(lookup.available()).isTrue();
        assertThat(lookup.reason()).isNull();
        WeatherInfo info = lookup.info();
        assertThat(info.city()).isEqualTo("上海");
        assertThat(info.date()).isEqualTo(TOMORROW);
        assertThat(info.condition()).isEqualTo("小雨");
        assertThat(info.rainProbability()).isEqualTo(72);
        assertThat(info.tempLow()).isEqualTo(12);
        assertThat(info.tempHigh()).isEqualTo(19);
        assertThat(info.source()).isEqualTo(WeatherInfo.SOURCE_REAL);
        assertThat(lookup.simulation()).isFalse();

        assertThat(service.provider()).isEqualTo("open-meteo");
        assertThat(service.verified()).isTrue();
        assertThat(service.lastError()).isNull();
        assertThat(degradedTotal(registry)).isZero();
        server.verify(1, getRequestedFor(urlPathEqualTo("/v1/forecast")));
    }

    // ==================== 5. 依赖类失败 → unavailable + 指标 ====================

    @Test
    @DisplayName("5xx → unavailable(server_error) 且 external.degraded{provider=weather} 递增")
    void serverErrorIsUnavailableAndDegradedMetricIncrements() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(aResponse().withStatus(500)));

        WeatherService service = new WeatherService(propsForServer(), catalog, registry);
        WeatherLookup lookup = service.forecast("上海", TOMORROW);

        assertThat(lookup.available()).isFalse();
        assertThat(lookup.reason()).isEqualTo(WeatherLookup.REASON_SERVER_ERROR);
        assertThat(lookup.message()).isEqualTo("天气服务异常");
        assertThat(degraded(registry, ExternalDegradationMetrics.REASON_SERVER_ERROR))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("坏 JSON → unavailable(bad_response) 且记一次降级（没有异常可归类的失败也要计数）")
    void malformedResponseIsUnavailableAndDegraded() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"daily\": ")));

        WeatherService service = new WeatherService(propsForServer(), catalog, registry);
        WeatherLookup lookup = service.forecast("上海", TOMORROW);

        assertThat(lookup.available()).isFalse();
        assertThat(lookup.reason()).isEqualTo(WeatherLookup.REASON_BAD_RESPONSE);
        assertThat(lookup.message()).isEqualTo("响应不可用");
        assertThat(degraded(registry, ExternalDegradationMetrics.REASON_BAD_RESPONSE))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("200 但字段缺失 → unavailable(bad_response)，不猜一个天气出来")
    void missingDailyFieldsIsUnavailable() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"daily\":{}}")));

        WeatherService service = new WeatherService(propsForServer(), catalog, registry);
        WeatherLookup lookup = service.forecast("上海", TOMORROW);

        assertThat(lookup.available()).isFalse();
        assertThat(lookup.reason()).isEqualTo(WeatherLookup.REASON_BAD_RESPONSE);
        // reason 与 external.degraded 的词表同源：指标/状态/文案不需要二次翻译
        assertThat(WeatherLookup.REASON_BAD_RESPONSE)
                .isEqualTo(ExternalDegradationMetrics.REASON_BAD_RESPONSE);
        assertThat(degraded(registry, ExternalDegradationMetrics.REASON_BAD_RESPONSE))
                .isGreaterThanOrEqualTo(1.0);
    }

    // ==================== 工具方法 ====================

    @Test
    @DisplayName("工具结果 JSON 契约：available/info/reason/message 都在，模型据此判断能不能用天气")
    void lookupKeepsStableJsonContract() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

        com.fasterxml.jackson.databind.JsonNode ok = mapper.readTree(mapper.writeValueAsString(
                WeatherLookup.available(new WeatherInfo("上海", TOMORROW, "晴", 5, 15, 24, WeatherInfo.SOURCE_REAL))));
        assertThat(ok.path("available").asBoolean()).isTrue();
        assertThat(ok.path("info").path("city").asText()).isEqualTo("上海");
        assertThat(ok.path("info").path("source").asText()).isEqualTo(WeatherInfo.SOURCE_REAL);
        assertThat(ok.path("reason").isNull()).isTrue();
        assertThat(ok.path("message").isNull()).isTrue();

        com.fasterxml.jackson.databind.JsonNode down = mapper.readTree(mapper.writeValueAsString(
                WeatherLookup.unavailable(WeatherLookup.REASON_OUTSIDE_WINDOW)));
        assertThat(down.path("available").asBoolean()).isFalse();
        assertThat(down.path("info").isNull()).isTrue();
        assertThat(down.path("reason").asText()).isEqualTo("outside-forecast-window");
        assertThat(down.path("message").asText()).isEqualTo("超出 16 天预报窗口");
    }

    @Test
    @DisplayName("reason 词表与 external.degraded 一致：指标/状态/文案不需要二次翻译")
    void reasonVocabularyMatchesDegradationMetric() {
        assertThat(WeatherLookup.REASON_CONNECTION_ERROR)
                .isEqualTo(ExternalDegradationMetrics.REASON_CONNECTION_ERROR);
        assertThat(WeatherLookup.REASON_TIMEOUT).isEqualTo(ExternalDegradationMetrics.REASON_TIMEOUT);
        assertThat(WeatherLookup.REASON_RATE_LIMITED)
                .isEqualTo(ExternalDegradationMetrics.REASON_RATE_LIMITED);
        assertThat(WeatherLookup.REASON_SERVER_ERROR)
                .isEqualTo(ExternalDegradationMetrics.REASON_SERVER_ERROR);
        assertThat(WeatherLookup.REASON_BAD_RESPONSE)
                .isEqualTo(ExternalDegradationMetrics.REASON_BAD_RESPONSE);
        // 输入边界用连字符命名，与「外部依赖降级」区分开，也不计入降级指标
        assertThat(WeatherLookup.REASON_OUTSIDE_WINDOW).isEqualTo("outside-forecast-window");
        assertThat(WeatherLookup.REASON_CITY_NOT_FOUND).isEqualTo("city-not-found");
    }

    private WeatherProperties propsForServer() {
        WeatherProperties props = new WeatherProperties();
        String base = "http://127.0.0.1:" + server.port();
        props.setForecastBaseUrl(base + "/v1/forecast");
        props.setGeocodeBaseUrl(base + "/v1/search");
        return props;
    }

    private static double degraded(SimpleMeterRegistry registry, String reason) {
        Counter counter = registry.find(ExternalDegradationMetrics.METRIC)
                .tag("provider", ExternalDegradationMetrics.PROVIDER_WEATHER)
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static double degradedTotal(SimpleMeterRegistry registry) {
        return registry.find(ExternalDegradationMetrics.METRIC)
                .tag("provider", ExternalDegradationMetrics.PROVIDER_WEATHER)
                .counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}