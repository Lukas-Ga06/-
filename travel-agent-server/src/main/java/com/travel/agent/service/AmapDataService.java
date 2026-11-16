package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.model.AmapPoi;
import com.travel.agent.model.AmapRoute;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import com.travel.agent.util.CoordinateTransform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 高德真实数据网关：POI 关键词搜索 + 驾车/步行路径规划。
 *
 * 数据源优先级（呼应架构 §4.10「优先经 MCP 通道」）：
 *   1) 已注册的高德 MCP 工具 mcp_*_maps_text_search / mcp_*_maps_direction_*（stdio 接 @amap/amap-maps-mcp-server）；
 *   2) 高德 REST（配置 AMAP_KEY 时，/v3/place/text、/v3/direction/driving|walking）；
 *   3) 不可用 —— 返回空列表 / null，由调用方降级（OnlineCityService 退回 LLM+地理编码，RouteService 退回 haversine）。
 * 所有外呼失败均 fail-soft，绝不阻断规划主链路；MCP 返回非 JSON 文本时自动退回 REST。
 *
 * <p>坐标契约：本类对调用方一律使用 WGS-84（入参与返回值）；高德要求 GCJ-02，
 * 只在拼请求参数、解析 {@code location} 时于本类内部转换。
 *
 * ToolRegistry 用 ObjectProvider 懒解析：避免 ToolRegistry→CalculateRouteTool→RouteService→本类→ToolRegistry 构造环。
 */
@Service
@Slf4j
public class AmapDataService {

    private static final String REST_BASE = "https://restapi.amap.com";

    private final ObjectMapper objectMapper;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final String amapKey;
    private final RestClient restClient;

    @Autowired
    public AmapDataService(ObjectProvider<ToolRegistry> toolRegistryProvider, ObjectMapper objectMapper,
                           @Value("${travel.amap.key:}") String amapKey, RestClient.Builder builder) {
        this.toolRegistryProvider = toolRegistryProvider;
        this.objectMapper = objectMapper;
        this.amapKey = amapKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(6000);
        this.restClient = builder.requestFactory(factory)
                .defaultHeader("User-Agent", "travel-agent/0.1 (amap-data)")
                .build();
    }

    /** 测试用：注入已构建（可被 MockRestServiceServer 绑定）的 RestClient，跳过超时工厂覆盖 */
    AmapDataService(ObjectProvider<ToolRegistry> toolRegistryProvider, ObjectMapper objectMapper,
                    String amapKey, RestClient restClient) {
        this.toolRegistryProvider = toolRegistryProvider;
        this.objectMapper = objectMapper;
        this.amapKey = amapKey;
        this.restClient = restClient;
    }

    /** 是否具备任一真实数据源（MCP 高德工具或 REST Key） */
    public boolean available() {
        return poiAvailable() || routeAvailable();
    }

    public boolean poiAvailable() {
        return mcpTool("maps_text_search") != null || amapKeyUsable();
    }

    public boolean routeAvailable() {
        return mcpTool("maps_direction_driving") != null || amapKeyUsable();
    }

    private boolean amapKeyUsable() {
        return amapKey != null && !amapKey.isBlank();
    }

    /**
     * POI 关键词搜索。types 为高德 POI 分类码（可空，如「风景名胜」080000 / 住宿服务 100000 / 餐饮服务 050000），
     * city 限定城市提高准确率，limit 上限（1~25）。失败或无数据源返回空列表。
     */
    public List<AmapPoi> searchPoi(String keywords, String city, String types, int limit) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        int max = Math.max(1, Math.min(limit, 25));
        AgentTool mcp = mcpTool("maps_text_search");
        if (mcp != null) {
            try {
                Object res = mcp.execute(args("keywords", keywords, "city", city, "types", types));
                List<AmapPoi> pois = parsePois(asJson(res), max);
                if (!pois.isEmpty()) {
                    return pois;
                }
            } catch (Exception e) {
                log.info("高德 MCP POI 搜索失败，退回 REST: {}", e.getMessage());
            }
        }
        if (!amapKeyUsable()) {
            return List.of();
        }
        try {
            StringBuilder uri = new StringBuilder(REST_BASE + "/v3/place/text?key=" + enc(amapKey)
                    + "&keywords=" + enc(keywords) + "&offset=" + max + "&extensions=all");
            if (city != null && !city.isBlank()) {
                uri.append("&city=").append(enc(city));
            }
            if (types != null && !types.isBlank()) {
                uri.append("&types=").append(enc(types));
            }
            JsonNode resp = restClient.get().uri(URI.create(uri.toString())).retrieve().body(JsonNode.class);
            return parsePois(resp, max);
        } catch (Exception e) {
            log.info("高德 REST POI 搜索失败（已降级）: {} - {}", keywords, e.getMessage());
            return List.of();
        }
    }

    /** 驾车路径：真实距离/时长；失败或无数据源返回 null（调用方降级估算） */
    public AmapRoute driving(double fromLat, double fromLng, double toLat, double toLng) {
        return direction("maps_direction_driving", "/v3/direction/driving", fromLat, fromLng, toLat, toLng);
    }

    /** 步行路径：真实距离/时长；失败或无数据源返回 null */
    public AmapRoute walking(double fromLat, double fromLng, double toLat, double toLng) {
        return direction("maps_direction_walking", "/v3/direction/walking", fromLat, fromLng, toLat, toLng);
    }

    private AmapRoute direction(String mcpSuffix, String restPath,
                                double fromLat, double fromLng, double toLat, double toLng) {
        double[] from = CoordinateTransform.wgs84ToGcj02(fromLat, fromLng);
        double[] to = CoordinateTransform.wgs84ToGcj02(toLat, toLng);
        String origin = coord(from[1], from[0]);
        String destination = coord(to[1], to[0]);
        AgentTool mcp = mcpTool(mcpSuffix);
        if (mcp != null) {
            try {
                Object res = mcp.execute(args("origin", origin, "destination", destination));
                AmapRoute route = parseRoute(asJson(res));
                if (route != null) {
                    return route;
                }
            } catch (Exception e) {
                log.info("高德 MCP 路径失败，退回 REST: {}", e.getMessage());
            }
        }
        if (!amapKeyUsable()) {
            return null;
        }
        try {
            String uri = REST_BASE + restPath + "?key=" + enc(amapKey)
                    + "&origin=" + origin + "&destination=" + destination;
            JsonNode resp = restClient.get().uri(URI.create(uri)).retrieve().body(JsonNode.class);
            return parseRoute(resp);
        } catch (Exception e) {
            log.info("高德 REST 路径失败（已降级）: {}", e.getMessage());
            return null;
        }
    }

    /** 扫描已注册的 mcp_*_{suffix} 工具（server 名任意），未接入返回 null */
    private AgentTool mcpTool(String suffix) {
        ToolRegistry registry = toolRegistryProvider.getIfAvailable();
        if (registry == null) {
            return null;
        }
        String want = "_" + suffix;
        for (AgentTool tool : registry.all().values()) {
            String name = tool.getName();
            if (name.startsWith("mcp_") && name.endsWith(want)) {
                return tool;
            }
        }
        return null;
    }

    private List<AmapPoi> parsePois(JsonNode resp, int max) {
        List<AmapPoi> list = new ArrayList<>();
        if (resp == null || !"1".equals(resp.path("status").asText())) {
            return list;
        }
        JsonNode pois = resp.path("pois");
        if (!pois.isArray()) {
            return list;
        }
        for (JsonNode p : pois) {
            String name = p.path("name").asText("").trim();
            double[] loc = parseLocation(p.path("location").asText(""));
            if (name.isEmpty() || loc == null) {
                continue;
            }
            list.add(new AmapPoi(p.path("id").asText(""), name, loc[0], loc[1],
                    p.path("address").asText(""), p.path("type").asText(""), p.path("tel").asText("")));
            if (list.size() >= max) {
                break;
            }
        }
        return list;
    }

    private AmapRoute parseRoute(JsonNode resp) {
        if (resp == null || !"1".equals(resp.path("status").asText())) {
            return null;
        }
        JsonNode paths = resp.path("route").path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            return null;
        }
        JsonNode first = paths.get(0);
        double dist = first.path("distance").asDouble(-1);
        double dur = first.path("duration").asDouble(-1);
        return (dist >= 0 && dur >= 0) ? new AmapRoute(dist, dur) : null;
    }

    /** 高德 location 格式为 "经度,纬度"（GCJ-02）→ WGS-84 [lat, lng]；非法/零坐标返回 null */
    private double[] parseLocation(String location) {
        if (location == null) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            if (lat == 0 && lng == 0) {
                return null;
            }
            return CoordinateTransform.gcj02ToWgs84(lat, lng);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode asJson(Object res) {
        if (res == null) {
            return null;
        }
        if (res instanceof JsonNode node) {
            return node;
        }
        String text = String.valueOf(res).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String coord(double lng, double lat) {
        return String.format(Locale.ROOT, "%.6f,%.6f", lng, lat);
    }

    private Map<String, Object> args(String... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null && !kv[i + 1].isBlank()) {
                map.put(kv[i], kv[i + 1]);
            }
        }
        return map;
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}