package com.travel.agent.mcp;

import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 高德地图 MCP 适配层（只读，不引入任何新的外部依赖）。
 *
 * <p>把 {@code @amap/amap-maps-mcp-server}（真实包 0.0.8）暴露的 12 个 {@code maps_*} 工具，映射成
 * 与厂商无关的语义名（{@link Capability}），并从 {@link ToolRegistry} 里发现「已经注册进来的高德工具」，
 * 产出「语义名 + 注册名 + 参数摘要」——供 IntentAgent 的{@code INVOKE_TOOL} 提示词使用。
 *
 * <p>职责边界：
 * <ul>
 *   <li>只做发现与描述，不调用远端、不缓存结果、不做任何写操作；</li>
 *   <li>注册名沿用桥接层规则 {@code mcp_<server>_<tool>}，因此服务器别名（yml 里的 {@code name}）
 *       改变时这里不需要改代码；</li>
 *   <li>参数摘要优先取注册时由真实 {@code inputSchema} 生成的描述（带 {@code *} 必填标记），
 *       拿不到时回退到本类内置的、从真实包 {@code build/index.js} 抄录的 schema。</li>
 * </ul>
 *
 * <p>未接入高德 MCP 时 {@link #discover(ToolRegistry)} 返回空列表、{@link #promptSummary(ToolRegistry)}
 * 返回空串，调用方拼提示词时可以原样跳过，不改变任何既有行为。
 */
@Slf4j
@Component
public class AmapMcpAdapter {

    /** 高德 MCP 的语义能力项（与厂商工具名解耦，便于提示词与前端展示复用） */
    public enum Capability {

        CITY_GEOCODE("city_geocode", "地址/地标解析为经纬度坐标"),
        REGEOCODE("reverse_geocode", "经纬度逆地理为行政区划地址"),
        ROUTE_DRIVING("route_driving", "驾车路径规划"),
        ROUTE_WALKING("route_walking", "步行路径规划"),
        ROUTE_TRANSIT("route_transit", "公交/地铁/火车综合路径规划"),
        ROUTE_BICYCLING("route_bicycling", "骑行路径规划"),
        MEASURE_DISTANCE("measure_distance", "两点/多起点距离测量"),
        POI_TEXT_SEARCH("poi_text_search", "关键词 POI 搜索"),
        POI_AROUND_SEARCH("poi_around_search", "周边 POI 搜索"),
        POI_DETAIL("poi_detail", "按 POI id 查详情"),
        WEATHER("weather", "城市天气查询"),
        IP_LOCATION("ip_location", "IP 定位");

        private final String semanticName;
        private final String label;

        Capability(String semanticName, String label) {
            this.semanticName = semanticName;
            this.label = label;
        }

        /** 语义名（提示词/日志里用的稳定名字） */
        public String semanticName() {
            return semanticName;
        }

        /** 中文说明 */
        public String label() {
            return label;
        }
    }

    /** 一个已接入的高德工具：语义能力 + 注册名 + 远端名 + 参数摘要 */
    public record AmapTool(Capability capability, String registryName, String remoteName,
                           List<String> params, List<String> required, String description) {

        /** 语义名，例如 {@code route_transit} */
        public String semanticName() {
            return capability.semanticName();
        }

        /** 供提示词用的单行摘要：语义名(中文) → 注册名（参数: a*, b） */
        public String toPromptLine() {
            StringBuilder sb = new StringBuilder("- ")
                    .append(capability.semanticName()).append('（').append(capability.label()).append("）: ")
                    .append(registryName);
            if (!params.isEmpty()) {
                sb.append("（参数: ");
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    String param = params.get(i);
                    sb.append(param).append(required.contains(param) ? "*" : "");
                }
                sb.append(required.isEmpty() ? "）" : "；*为必填）");
            }
            return sb.toString();
        }
    }

    /** 真实包 build/index.js 里的工具名 → 语义能力（顺序与包内定义无关） */
    private static final Map<String, Capability> TOOL_TO_CAPABILITY = new LinkedHashMap<>();

    /** 真实包 build/index.js 里各工具的 inputSchema（params / required），用于描述缺失时兜底 */
    private static final Map<String, Schema> FALLBACK_SCHEMA = new LinkedHashMap<>();

    /** 一个工具的参数名与必填集合 */
    private record Schema(List<String> params, List<String> required) { }

    static {
        map("maps_geo", Capability.CITY_GEOCODE, new String[]{"address", "city"}, new String[]{"address"});
        map("maps_regeocode", Capability.REGEOCODE, new String[]{"location"}, new String[]{"location"});
        map("maps_direction_driving", Capability.ROUTE_DRIVING,
                new String[]{"origin", "destination"}, new String[]{"origin", "destination"});
        map("maps_direction_walking", Capability.ROUTE_WALKING,
                new String[]{"origin", "destination"}, new String[]{"origin", "destination"});
        map("maps_direction_transit_integrated", Capability.ROUTE_TRANSIT,
                new String[]{"origin", "destination", "city", "cityd"},
                new String[]{"origin", "destination", "city", "cityd"});
        map("maps_bicycling", Capability.ROUTE_BICYCLING,
                new String[]{"origin", "destination"}, new String[]{"origin", "destination"});
        map("maps_distance", Capability.MEASURE_DISTANCE,
                new String[]{"origins", "destination", "type"}, new String[]{"origins", "destination"});
        map("maps_text_search", Capability.POI_TEXT_SEARCH,
                new String[]{"keywords", "city", "types"}, new String[]{"keywords"});
        map("maps_around_search", Capability.POI_AROUND_SEARCH,
                new String[]{"keywords", "location", "radius"}, new String[]{"location"});
        map("maps_search_detail", Capability.POI_DETAIL, new String[]{"id"}, new String[]{"id"});
        map("maps_weather", Capability.WEATHER, new String[]{"city"}, new String[]{"city"});
        map("maps_ip_location", Capability.IP_LOCATION, new String[]{"ip"}, new String[]{"ip"});
    }

    private static void map(String remoteTool, Capability capability, String[] params, String[] required) {
        TOOL_TO_CAPABILITY.put(remoteTool, capability);
        FALLBACK_SCHEMA.put(remoteTool, new Schema(List.copyOf(Arrays.asList(params)),
                List.copyOf(Arrays.asList(required))));
    }

    /** 高德 MCP 真实工具名 → 语义能力；非高德工具返回 {@link Optional#empty()} */
    public Optional<Capability> capabilityOf(String remoteToolName) {
        if (remoteToolName == null || remoteToolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(TOOL_TO_CAPABILITY.get(remoteToolName.trim()));
    }

    /** 是否已接入高德 MCP（注册表里至少有一个 {@code maps_*} 工具） */
    public boolean isAvailable(ToolRegistry registry) {
        return !discover(registry).isEmpty();
    }

    /**
     * 从 {@link ToolRegistry} 发现已注册的高德工具（顺序稳定：按语义能力声明顺序）。
     * 未启用 MCP / 未接入高德 / 注册表为空都返回空列表，不抛异常。
     */
    public List<AmapTool> discover(ToolRegistry registry) {
        List<AmapTool> found = new ArrayList<>();
        if (registry == null || registry.all() == null) {
            return found;
        }
        for (AgentTool tool : registry.all().values()) {
            String registryName = tool == null ? null : tool.getName();
            String remoteName = remoteToolName(registryName);
            if (remoteName == null) {
                continue;
            }
            Capability capability = TOOL_TO_CAPABILITY.get(remoteName);
            if (capability == null) {
                continue;
            }
            ParamSummary parsed = parseParamHint(tool.getDescription());
            Schema fallback = FALLBACK_SCHEMA.get(remoteName);
            List<String> params = parsed.params().isEmpty() && fallback != null
                    ? fallback.params() : parsed.params();
            List<String> required = parsed.params().isEmpty() && fallback != null
                    ? fallback.required() : parsed.required();
            found.add(new AmapTool(capability, registryName, remoteName, params, required,
                    tool.getDescription()));
        }
        found.sort((a, b) -> a.capability().ordinal() - b.capability().ordinal());
        return found;
    }

    /** 某语义能力当前对应的注册名（如 {@code mcp_amap_maps_direction_transit_integrated}） */
    public Optional<String> registryName(ToolRegistry registry, Capability capability) {
        return discover(registry).stream()
                .filter(t -> t.capability() == capability)
                .map(AmapTool::registryName)
                .findFirst();
    }

    /**
     * 生成可直接拼进 IntentAgent / INVOKE_TOOL 提示词的高德工具摘要。
     * 没有任何高德工具时返回空串（调用方原样拼接即无副作用）。
     */
    public String promptSummary(ToolRegistry registry) {
        List<AmapTool> tools = discover(registry);
        if (tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("可用高德地图工具（真实数据源，只读；参数带 * 为必填）：\n");
        for (AmapTool tool : tools) {
            sb.append(tool.toPromptLine()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 从注册名 {@code mcp_<server>_<tool>} 反推远端工具名。桥接层会把 server 别名与工具名都
     * {@code sanitize}（小写、非字母数字换成下划线），所以这里只做前缀剥离，再由白名单校验。
     */
    private String remoteToolName(String registryName) {
        if (registryName == null || !registryName.startsWith("mcp_")) {
            return null;
        }
        int separator = registryName.indexOf('_', "mcp_".length());
        if (separator < 0 || separator + 1 >= registryName.length()) {
            return null;
        }
        return registryName.substring(separator + 1);
    }

    /** 解析桥接层注入的参数提示：「（参数: address*(待解析地址), city；*为必填）」 */
    static ParamSummary parseParamHint(String description) {
        if (description == null) {
            return new ParamSummary(List.of(), List.of());
        }
        int start = description.indexOf("参数: ");
        if (start < 0) {
            return new ParamSummary(List.of(), List.of());
        }
        int end = description.lastIndexOf('）');
        String body = end < 0 ? description.substring(start + 4) : description.substring(start + 4, end);
        int semicolon = body.indexOf('；');
        if (semicolon >= 0) {
            body = body.substring(0, semicolon);
        }
        List<String> params = new ArrayList<>();
        List<String> required = new ArrayList<>();
        for (String raw : body.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            int paren = token.indexOf('(');
            if (paren >= 0) {
                token = token.substring(0, paren).trim();
            }
            boolean isRequired = token.endsWith("*");
            if (isRequired) {
                token = token.substring(0, token.length() - 1).trim();
            }
            if (token.isEmpty()) {
                continue;
            }
            params.add(token);
            if (isRequired) {
                required.add(token);
            }
        }
        return new ParamSummary(params, required);
    }

    /** 解析出的参数名与必填集合 */
    record ParamSummary(List<String> params, List<String> required) { }
}