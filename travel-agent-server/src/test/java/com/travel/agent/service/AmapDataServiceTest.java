package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.model.AmapPoi;
import com.travel.agent.model.AmapRoute;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

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
 * 以及高德真实响应（location "经度,纬度"、paths.distance/duration 字符串）的解析。
 * REST 用 MockRestServiceServer，MCP 用注册进 ToolRegistry 的假工具，全程不外呼。
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
        server.verify();
    }

    @Test
    void blankKeywordsShortCircuit() {
        AmapDataService svc = new AmapDataService(provider(null), MAPPER, "test-key", RestClient.builder().build());
        assertTrue(svc.searchPoi("  ", "上海", null, 5).isEmpty());
    }
}