package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.config.AmapProperties;
import com.travel.agent.model.AmapPoi;
import com.travel.agent.model.AmapRoute;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import com.travel.agent.util.CoordinateTransform;
import com.travel.agent.util.ErrorSummary;
import com.travel.agent.util.PolylineSimplify;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

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
 * 只在拼请求参数、解析 {@code location} / {@code polyline} 时于本类内部转换。
 *
 * <p>可观测性（V2.2 验收 P2/P6）：
 * <ul>
 *   <li>失败不再静默：无数据源、非法 Key（如 10009 USERKEY_PLAT_NOMATCH）、QPS 限流（10021）
 *       都会留一条带 infocode 的日志；</li>
 *   <li>本类记录**最近一次真实路径调用的成败**（{@link #routeVerified()} / {@link #routeLastError()}），
 *       由 {@link RouteService} 与 {@code /api/system/status} 如实自报——配置了不等于可用（ADR-006）；</li>
 *   <li>QPS 限流做**一次**退避重试（200~400ms 带抖动）：高德的限流多是瞬时窗口，重试一次即可，
 *       重试仍失败才降级。</li>
 * </ul>
 *
 * ToolRegistry 用 ObjectProvider 懒解析：避免 ToolRegistry→CalculateRouteTool→RouteService→本类→ToolRegistry 构造环。
 */
@Service
@Slf4j
public class AmapDataService {

    private static final String REST_BASE = "https://restapi.amap.com";

    /** 高德 QPS 超限：CUQPS_HAS_EXCEEDED_THE_LIMIT（验收报告 P6 实测 8 并发 3 次命中） */
    static final String INFOCODE_QPS_LIMIT = "10021";
    /** 限流重试的退避基数（毫秒）：配合 {@link #QPS_RETRY_JITTER_MS} 落在 220~400ms 区间 */
    static final long QPS_RETRY_BASE_DELAY_MS = 220L;
    /** 限流重试抖动上限（毫秒）：多实例/多线程同时重试时错开，避免二次撞墙 */
    static final long QPS_RETRY_JITTER_MS = 180L;
    /** 折线抽稀容差（米）：10~15 米足够保留城市道路形状，画布上看不出差异 */
    static final double POLYLINE_TOLERANCE_METERS = 12.0;
    /** 折线点数上限：与前端渲染成本、缓存条目大小共同决定 */
    static final int POLYLINE_MAX_POINTS = 200;

    private final ObjectMapper objectMapper;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final AmapProperties amap;
    private final RestClient restClient;

    /**
     * 最近一次真实路径调用结果：{@code succeeded=false, error=null} 表示「本次进程还没真实调用过」，
     * 等价于「配置了但尚未验证」，状态页不得据此宣称 amap（P2）。
     */
    private final AtomicReference<RouteCallOutcome> lastRouteCall =
            new AtomicReference<>(RouteCallOutcome.NOT_CALLED);

    /**
     * 路径调用结果快照。
     *
     * @param succeeded 最近一次真实调用是否拿到可用路线（MCP 或 REST 任一成功即为 true）
     * @param error     失败原因（含 infocode，已脱敏，不含 Key）；未调用过时为 null
     */
    public record RouteCallOutcome(boolean succeeded, String error) {
        static final RouteCallOutcome NOT_CALLED = new RouteCallOutcome(false, null);
    }

    @Autowired
    public AmapDataService(ObjectProvider<ToolRegistry> toolRegistryProvider, ObjectMapper objectMapper,
                           AmapProperties amap, RestClient.Builder builder) {
        this.toolRegistryProvider = toolRegistryProvider;
        this.objectMapper = objectMapper;
        this.amap = amap;
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
        this.amap = new AmapProperties();
        this.amap.setKey(amapKey);
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
        return amap.usable();
    }

    /** 最近一次真实路径调用是否成功：状态页据此决定能否自报 routeProvider=amap */
    public boolean routeVerified() {
        return lastRouteCall.get().succeeded();
    }

    /** 最近一次真实路径调用失败原因（含 infocode，不含 Key）；未调用过/成功时为 null */
    public String routeLastError() {
        return lastRouteCall.get().error();
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
                // P3：带上根因类型（SocketTimeout / ConnectException），不要只剩包装异常的一句话
                log.info("高德 MCP POI 搜索失败，退回 REST: {}", ErrorSummary.describe(e));
            }
        }
        if (!amapKeyUsable()) {
            return List.of();
        }
        try {
            StringBuilder uri = new StringBuilder(REST_BASE + "/v3/place/text?key=" + enc(amap.getKey())
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
            log.info("高德 REST POI 搜索失败（已降级）: {} - {}",
                    keywords, ErrorSummary.describe(e, amap.getKey()));
            return List.of();
        }
    }

    /** 驾车路径：真实距离/时长/几何；失败或无数据源返回 null（调用方降级估算） */
    public AmapRoute driving(double fromLat, double fromLng, double toLat, double toLng) {
        return direction("maps_direction_driving", "/v3/direction/driving", fromLat, fromLng, toLat, toLng);
    }

    /** 步行路径：真实距离/时长/几何；失败或无数据源返回 null */
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
                    // MCP 也是真实数据源：成功即视为「已验证」，可对外自报 amap
                    markRouteCallSucceeded();
                    return route;
                }
                log.warn("高德 MCP 路径无可用结果（status/route.paths 缺失），退回 REST");
            } catch (Exception e) {
                log.warn("高德 MCP 路径失败（{}），退回 REST", ErrorSummary.describe(e));
            }
        }
        if (!amapKeyUsable()) {
            String reason = mcp != null ? "高德 MCP 调用失败且未配置 AMAP_KEY" : "未配置 AMAP_KEY 或高德 MCP";
            markRouteCallFailed(reason);
            return null;
        }
        return restDirection(restPath, origin, destination);
    }

    /**
     * 高德 REST 路径：对 QPS 限流（status=0 & infocode=10021）做一次退避重试。
     *
     * <p>为什么只重试 10021：它代表「这一秒/这个窗口超了」，几毫秒后重试通常就成功；
     * 其它 infocode（10001 INVALID_USER_KEY、10009 USERKEY_PLAT_NOMATCH、20000 服务异常…）
     * 重试也不会变好，重试只会把一次规划拖得更慢，所以直接告警 + 降级。
     */
    private AmapRoute restDirection(String restPath, String origin, String destination) {
        String uri = REST_BASE + restPath + "?key=" + enc(amap.getKey())
                + "&origin=" + origin + "&destination=" + destination;
        for (int attempt = 0; attempt <= 1; attempt++) {
            JsonNode resp;
            try {
                resp = restClient.get().uri(URI.create(uri)).retrieve().body(JsonNode.class);
            } catch (Exception e) {
                String reason = ErrorSummary.describe(e, amap.getKey());
                log.warn("高德 REST 路径不可用（{}），已降级估算", reason);
                markRouteCallFailed(reason);
                return null;
            }
            if (resp != null && "1".equals(resp.path("status").asText())) {
                AmapRoute route = parseRoute(resp);
                if (route != null) {
                    markRouteCallSucceeded();
                    return route;
                }
                String reason = "status=1 但 route.paths[0] 缺少 distance/duration";
                log.warn("高德 REST 路径异常（{}），已降级估算", reason);
                markRouteCallFailed(reason);
                return null;
            }
            String infocode = resp == null ? "" : resp.path("infocode").asText("");
            String info = resp == null ? "" : resp.path("info").asText("");
            if (INFOCODE_QPS_LIMIT.equals(infocode) && attempt == 0) {
                long delay = nextQpsRetryDelayMs();
                log.warn("高德路径被 QPS 限流（infocode=10021 CUQPS_HAS_EXCEEDED_THE_LIMIT），{}ms 后重试一次", delay);
                if (!sleepQuietly(delay)) {
                    String reason = "infocode=10021（重试等待被中断）";
                    markRouteCallFailed(reason);
                    return null;
                }
                continue;
            }
            String reason = "infocode=" + dash(infocode) + ", info=" + dash(info);
            log.warn("高德路径调用失败（{}），已降级估算", reason);
            markRouteCallFailed(reason);
            return null;
        }
        return null; // 理论不可达：循环内每条分支都会 return
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
            double[] loc = CoordinateTransform.parseGcj02Location(p.path("location").asText(""));
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
        if (dist < 0 || dur < 0) {
            return null;
        }
        // 几何：route.paths[0].steps[].polyline（"lng,lat;lng,lat"，GCJ-02）逐点转 WGS-84 后抽稀。
        // MCP 通道若返回同构 JSON 也会走到这里；没有 steps/polyline 时给空列表（fail-soft，不造假点）。
        List<double[]> geometry = PolylineSimplify.simplify(
                collectPolyline(first), POLYLINE_TOLERANCE_METERS, POLYLINE_MAX_POINTS);
        return new AmapRoute(dist, dur, geometry);
    }

    /** 拼接 paths[0] 下所有 step 的 polyline，返回 WGS-84 的 [lat, lng] 序列（已去重相邻重合点） */
    private List<double[]> collectPolyline(JsonNode path) {
        JsonNode steps = path.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            return List.of();
        }
        List<double[]> points = new ArrayList<>();
        for (JsonNode step : steps) {
            String text = step.path("polyline").asText("");
            if (text.isBlank()) {
                continue;
            }
            for (String token : text.split(";")) {
                double[] point = CoordinateTransform.parseGcj02Location(token);
                if (point == null) {
                    continue; // 非法点 / "0,0"：跳过而不是用假点占位
                }
                if (!points.isEmpty()) {
                    double[] last = points.get(points.size() - 1);
                    // 相邻 step 的首尾是同一个路口，去掉重复点（否则抽稀后的首尾判断会变模糊）
                    if (Math.abs(last[0] - point[0]) < 1e-9 && Math.abs(last[1] - point[1]) < 1e-9) {
                        continue;
                    }
                }
                points.add(point);
            }
        }
        return points;
    }

    /** 限流退避：基础值与抖动之和，落在 [{@value #QPS_RETRY_BASE_DELAY_MS}, 400] 毫秒 */
    private static long nextQpsRetryDelayMs() {
        return QPS_RETRY_BASE_DELAY_MS + ThreadLocalRandom.current().nextLong(QPS_RETRY_JITTER_MS + 1);
    }

    /** 不可中断主线程的退避等待：被中断时恢复中断位并让调用方直接降级 */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void markRouteCallSucceeded() {
        lastRouteCall.set(new RouteCallOutcome(true, null));
    }

    private void markRouteCallFailed(String reason) {
        lastRouteCall.set(new RouteCallOutcome(false, reason));
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

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
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
