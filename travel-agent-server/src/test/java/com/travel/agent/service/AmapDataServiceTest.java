package com.travel.agent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.model.AmapPoi;
import com.travel.agent.model.AmapRoute;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolRegistry;
import com.travel.agent.util.CoordinateTransform;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 高德真实数据网关：验证 MCP 优先 → REST 兜底 → 无数据源/失败 fail-soft 三条路径，
 * 以及高德真实响应（location "经度,纬度"、paths.distance/duration 字符串、steps[].polyline）的解析。
 * REST 用 MockRestServiceServer，MCP 用注册进 ToolRegistry 的假工具，全程不外呼。
 *
 * <p>V2.2 验收 P2/P6 追加：10021 限流一次退避重试、重试仍失败/非法 Key 必须 WARN（带 infocode）并如实记录
 * 「最近一次调用失败」，polyline 拼接 → GCJ-02→WGS-84 → 抽稀（≤200 点）。
 */
class AmapDataServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POI_JSON = """
            {"status":"1","count":"1","pois":[
              {"id":"B001","name":"外滩","location":"121.490317,31.222771",
               "address":"中山东一路","type":"风景名胜;旅游景点","tel":"021-12345"}]}
            """;
    private static final String DRIVE_JSON = """
            {"status":"1","route":{"origin":"121.490317,31.222771","destination":"121.500000,31.240000",
              "paths":[{"distance":"12345","duration":"1800"}]}}
            """;
    /** 带 steps[].polyline 的真实驾车响应（GCJ-02 "lng,lat;lng,lat"），两条 step 在路口共点 */
    private static final String DRIVE_WITH_POLYLINE_JSON = """
            {"status":"1","route":{"paths":[{
              "distance":"12345","duration":"1800",
              "steps":[
                {"polyline":"121.490317,31.222771;121.500000,31.240000"},
                {"polyline":"121.500000,31.240000;121.510000,31.250000"}]}]}}
            """;
    /** P6：实测 8 并发命中 3 次的 QPS 限流响应 */
    private static final String QPS_LIMIT_JSON = """
            {"status":"0","info":"CUQPS_HAS_EXCEEDED_THE_LIMIT","infocode":"10021"}
            """;
    /** 无效 Key：验收报告里「后端日志无任何错误」的那一种响应 */
    private static final String INVALID_KEY_JSON = """
            {"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}
            """;
    /** Key 平台不匹配（Web端 JS API Key 用在了 Web 服务接口上） */
    private static final String PLATFORM_MISMATCH_JSON = """
            {"status":"0","info":"USERKEY_PLAT_NOMATCH","infocode":"10009"}
            """;

    @SuppressWarnings("unchecked")
    private ObjectProvider<ToolRegistry> provider(ToolRegistry registry) {
        ObjectProvider<ToolRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(registry);
        return p;
    }

    private AgentTool fakeMcp(String name, String result) {
        return new AgentTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "fake amap mcp"; }
            @Override public RiskLevel getRiskLevel() { return RiskLevel.LOW; }
            @Override public Object execute(Map<String, Object> args) { return result; }
        };
    }

    private AmapDataService restService(String key, MockRestServiceServer[] outServer) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        outServer[0] = server;
        return new AmapDataService(provider(new ToolRegistry(List.of())), MAPPER, key, builder.build());
    }

    /** 挂内存 appender 到被测 logger：WARN 断言针对「日志里到底有没有错误」这一验收项 */
    private static ListAppender<ILoggingEvent> attachLogs(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static long warnCount(ListAppender<ILoggingEvent> logs, String text) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(text))
                .count();
    }

    /** 构造一条「纬度方向左右各摆 0.5 度」的锯齿折线（偏离远超任何抽稀容差，用来触发点数上限兜底） */
    private static String jaggedDriveJson(int count) {
        StringBuilder polyline = new StringBuilder();
        for (int i = 0; i < count; i++) {
            double lat = 31.2 + i * 0.00002 + (i % 2 == 0 ? 0.5 : -0.5);
            double lng = 121.5 + i * 0.00002;
            polyline.append(String.format(Locale.ROOT, "%.6f,%.6f", lng, lat));
            if (i < count - 1) {
                polyline.append(';');
            }
        }
        return "{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"9000\",\"duration\":\"900\","
                + "\"steps\":[{\"polyline\":\"" + polyline + "\"}]}]}}";
    }

    @Test
    void restPoiSearchParsesRealResponse() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/place/text")))
                .andRespond(withSuccess(POI_JSON, MediaType.APPLICATION_JSON));

        List<AmapPoi> pois = svc.searchPoi("外滩", "上海", null, 5);
        assertEquals(1, pois.size());
        AmapPoi poi = pois.get(0);
        assertEquals("外滩", poi.name());
        // 高德返回 GCJ-02；对外统一落成 WGS-84，否则前端高德渲染会二次偏移。
        assertEquals(31.2247502775, poi.lat(), 1e-6);
        assertEquals(121.4858355938, poi.lng(), 1e-6);
        assertEquals("中山东一路", poi.address());
        assertTrue(svc.poiAvailable());
        holder[0].verify();
    }

    @Test
    void mcpPoiIsPreferredOverRest() {
        ToolRegistry registry = new ToolRegistry(List.of(fakeMcp("mcp_amap_maps_text_search", POI_JSON)));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AmapDataService svc = new AmapDataService(provider(registry), MAPPER, "test-key", builder.build());

        List<AmapPoi> pois = svc.searchPoi("外滩", "上海", null, 5);
        assertEquals(1, pois.size());
        assertEquals("外滩", pois.get(0).name());
        // MCP 命中后绝不再走 REST：mock server 无任何期望，若发起请求会失败
        server.verify();
    }

    @Test
    void mcpUnparseableTextFallsBackToRest() {
        ToolRegistry registry = new ToolRegistry(List.of(fakeMcp("mcp_amap_maps_text_search", "抱歉，没有查到结果")));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AmapDataService svc = new AmapDataService(provider(registry), MAPPER, "test-key", builder.build());
        server.expect(requestTo(containsString("/v3/place/text")))
                .andRespond(withSuccess(POI_JSON, MediaType.APPLICATION_JSON));

        List<AmapPoi> pois = svc.searchPoi("外滩", "上海", null, 5);
        assertEquals(1, pois.size(), "MCP 返回非 JSON 时应退回 REST");
        server.verify();
    }

    @Test
    void noKeyNoMcpReturnsEmptyWithoutHttp() {
        AmapDataService svc = new AmapDataService(provider(null), MAPPER, "", RestClient.builder().build());
        assertFalse(svc.available());
        assertTrue(svc.searchPoi("外滩", "上海", null, 5).isEmpty());
        assertNull(svc.driving(31.22, 121.49, 31.24, 121.50));
        // 没有真实数据源 = 没有真实调用：不能对外宣称「已验证」
        assertFalse(svc.routeVerified());
    }

    @Test
    void restFailureIsFailSoft() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/place/text"))).andRespond(withServerError());
        assertTrue(svc.searchPoi("外滩", "上海", null, 5).isEmpty(), "外呼失败应降级为空列表而非抛异常");
        holder[0].verify();
    }

    @Test
    void restStatusNotOneReturnsEmpty() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/place/text")))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}", MediaType.APPLICATION_JSON));
        assertTrue(svc.searchPoi("外滩", "上海", null, 5).isEmpty());
        holder[0].verify();
    }

    @Test
    void drivingRestParsesDistanceAndDuration() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(DRIVE_JSON, MediaType.APPLICATION_JSON));

        AmapRoute route = svc.driving(31.222771, 121.490317, 31.24, 121.50);
        assertEquals(12345.0, route.distanceMeters(), 1e-6);
        assertEquals(1800.0, route.durationSeconds(), 1e-6);
        assertTrue(svc.routeAvailable());
        assertTrue(svc.routeVerified());
        holder[0].verify();
    }

    @Test
    void drivingConvertsWgsToGcjBeforeCallingAmap() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("origin=121.494782")))
                .andRespond(withSuccess(DRIVE_JSON, MediaType.APPLICATION_JSON));

        svc.driving(31.222771, 121.490317, 31.24, 121.50);
        holder[0].verify();
    }

    @Test
    void walkingRestParsesRoute() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/direction/walking")))
                .andRespond(withSuccess("{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"900\",\"duration\":\"720\"}]}}",
                        MediaType.APPLICATION_JSON));

        AmapRoute route = svc.walking(31.222771, 121.490317, 31.24, 121.50);
        assertEquals(900.0, route.distanceMeters(), 1e-6);
        assertEquals(720.0, route.durationSeconds(), 1e-6);
        holder[0].verify();
    }

    @Test
    void mcpDrivingIsPreferredOverRest() {
        ToolRegistry registry = new ToolRegistry(List.of(fakeMcp("mcp_amap_maps_direction_driving", DRIVE_JSON)));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AmapDataService svc = new AmapDataService(provider(registry), MAPPER, "test-key", builder.build());

        AmapRoute route = svc.driving(31.222771, 121.490317, 31.24, 121.50);
        assertEquals(12345.0, route.distanceMeters(), 1e-6);
        // MCP 也是真实数据源：成功即「已验证」
        assertTrue(svc.routeVerified());
        server.verify();
    }

    @Test
    void blankKeywordsShortCircuit() {
        AmapDataService svc = new AmapDataService(provider(null), MAPPER, "test-key", RestClient.builder().build());
        assertTrue(svc.searchPoi("  ", "上海", null, 5).isEmpty());
    }

    // ------------------------------------------------------------------
    // V2.2 验收 P6：QPS 限流（10021）一次退避重试 + 可观测
    // ------------------------------------------------------------------

    @Test
    void qpsLimitIsRetriedOnceAndSucceeds() {
        Logger logger = (Logger) LoggerFactory.getLogger(AmapDataService.class);
        ListAppender<ILoggingEvent> logs = attachLogs(logger);
        try {
            MockRestServiceServer[] holder = new MockRestServiceServer[1];
            AmapDataService svc = restService("test-key", holder);
            holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                    .andRespond(withSuccess(QPS_LIMIT_JSON, MediaType.APPLICATION_JSON));
            holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                    .andRespond(withSuccess(DRIVE_JSON, MediaType.APPLICATION_JSON));

            AmapRoute route = svc.driving(31.222771, 121.490317, 31.24, 121.50);

            assertThat(route).isNotNull();
            assertThat(route.distanceMeters()).isEqualTo(12345.0);
            assertThat(svc.routeVerified()).isTrue();
            assertThat(svc.routeLastError()).isNull();
            // 限流本身要留痕（验收报告点名的「后端日志无任何错误」）
            assertThat(warnCount(logs, "10021")).isGreaterThanOrEqualTo(1);
            holder[0].verify();
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void qpsLimitRetryStillFailingWarnsAndDegrades() {
        Logger logger = (Logger) LoggerFactory.getLogger(AmapDataService.class);
        ListAppender<ILoggingEvent> logs = attachLogs(logger);
        try {
            MockRestServiceServer[] holder = new MockRestServiceServer[1];
            AmapDataService svc = restService("test-key", holder);
            holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                    .andRespond(withSuccess(QPS_LIMIT_JSON, MediaType.APPLICATION_JSON));
            holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                    .andRespond(withSuccess(QPS_LIMIT_JSON, MediaType.APPLICATION_JSON));

            AmapRoute route = svc.driving(31.222771, 121.490317, 31.24, 121.50);

            assertThat(route).isNull();
            // 首次限流一条 + 重试仍失败一条，都要带 infocode
            assertThat(warnCount(logs, "10021")).isEqualTo(2);
            assertThat(svc.routeVerified()).isFalse();
            assertThat(svc.routeLastError()).contains("10021").doesNotContain("test-key");
            holder[0].verify(); // 恰好两次请求：只重试一次，不无限重试
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void platformMismatchKeyWarnsOnceWithInfocode() {
        Logger logger = (Logger) LoggerFactory.getLogger(AmapDataService.class);
        ListAppender<ILoggingEvent> logs = attachLogs(logger);
        try {
            MockRestServiceServer[] holder = new MockRestServiceServer[1];
            AmapDataService svc = restService("test-key", holder);
            holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                    .andRespond(withSuccess(PLATFORM_MISMATCH_JSON, MediaType.APPLICATION_JSON));

            assertThat(svc.driving(31.222771, 121.490317, 31.24, 121.50)).isNull();

            // 非 10021 不做重试，但必须 WARN 一次并带上 infocode
            assertThat(warnCount(logs, "10009")).isEqualTo(1);
            assertThat(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("USERKEY_PLAT_NOMATCH"))).isTrue();
            assertThat(svc.routeLastError()).contains("10009").doesNotContain("test-key");
            holder[0].verify(); // 只请求一次
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void invalidKeyWarnsWithInfocodeAndStaysUnverified() {
        Logger logger = (Logger) LoggerFactory.getLogger(AmapDataService.class);
        ListAppender<ILoggingEvent> logs = attachLogs(logger);
        try {
            MockRestServiceServer[] holder = new MockRestServiceServer[1];
            AmapDataService svc = restService("test-key", holder);
            holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                    .andRespond(withSuccess(INVALID_KEY_JSON, MediaType.APPLICATION_JSON));

            assertThat(svc.driving(31.222771, 121.490317, 31.24, 121.50)).isNull();
            assertThat(warnCount(logs, "10001")).isEqualTo(1);
            assertThat(svc.routeVerified()).isFalse();
            holder[0].verify();
        } finally {
            logger.detachAppender(logs);
        }
    }

    // ------------------------------------------------------------------
    // 真实道路几何：steps[].polyline 拼接 → GCJ-02→WGS-84 → 抽稀
    // ------------------------------------------------------------------

    @Test
    void drivingParsesPolylineFromStepsAsWgs84() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(DRIVE_WITH_POLYLINE_JSON, MediaType.APPLICATION_JSON));

        AmapRoute route = svc.driving(31.222771, 121.490317, 31.24, 121.50);

        // 两条 step 在路口共点 → 拼接后 3 个不同点，且都转成 WGS-84
        assertThat(route.polyline()).hasSize(3);
        double[] first = CoordinateTransform.gcj02ToWgs84(31.222771, 121.490317);
        double[] middle = CoordinateTransform.gcj02ToWgs84(31.240000, 121.500000);
        double[] last = CoordinateTransform.gcj02ToWgs84(31.250000, 121.510000);
        assertThat(route.polyline().get(0)[0]).isCloseTo(first[0], within(1e-9));
        assertThat(route.polyline().get(0)[1]).isCloseTo(first[1], within(1e-9));
        assertThat(route.polyline().get(1)[0]).isCloseTo(middle[0], within(1e-9));
        assertThat(route.polyline().get(1)[1]).isCloseTo(middle[1], within(1e-9));
        assertThat(route.polyline().get(2)[0]).isCloseTo(last[0], within(1e-9));
        assertThat(route.polyline().get(2)[1]).isCloseTo(last[1], within(1e-9));
        // 格式约定：[lat, lng]（纬度在前）
        assertThat(route.polyline().get(0)[0]).isBetween(31.0, 32.0);
        assertThat(route.polyline().get(0)[1]).isBetween(121.0, 122.0);
    }

    @Test
    void polylineStaysWithin200PointsKeepingBothEndpoints() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(jaggedDriveJson(1200), MediaType.APPLICATION_JSON));

        AmapRoute route = svc.driving(31.222771, 121.490317, 31.24, 121.50);

        assertThat(route.polyline()).isNotEmpty();
        assertThat(route.polyline()).hasSizeLessThanOrEqualTo(200);
        assertThat(route.polyline()).hasSizeLessThan(1200);   // 确实做过抽稀
        double[] first = CoordinateTransform.gcj02ToWgs84(31.7, 121.5);
        double[] last = CoordinateTransform.gcj02ToWgs84(31.2 + 1199 * 0.00002 - 0.5, 121.5 + 1199 * 0.00002);
        assertThat(route.polyline().get(0)[0]).isCloseTo(first[0], within(1e-9));
        assertThat(route.polyline().get(0)[1]).isCloseTo(first[1], within(1e-9));
        double[] lastPoint = route.polyline().get(route.polyline().size() - 1);
        assertThat(lastPoint[0]).isCloseTo(last[0], within(1e-9));
        assertThat(lastPoint[1]).isCloseTo(last[1], within(1e-9));
    }

    @Test
    void drivingWithoutStepsHasEmptyPolyline() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        AmapDataService svc = restService("test-key", holder);
        holder[0].expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(DRIVE_JSON, MediaType.APPLICATION_JSON));

        AmapRoute route = svc.driving(31.222771, 121.490317, 31.24, 121.50);
        // 没有几何就给空列表，绝不用两端点伪造一条路径
        assertThat(route.polyline()).isEmpty();
    }
}
