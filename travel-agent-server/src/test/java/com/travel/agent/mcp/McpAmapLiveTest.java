package com.travel.agent.mcp;

import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高德 MCP 12 个工具的**真实 Key 业务输出核对**（人工按需运行，不进 CI）。
 *
 * <p>为什么单独一类：{@code mcp-amap} 文档 §5.2 记的缺口是「12 个工具只验了能被注册与调用到，
 * 各自的业务输出没有逐一核对」——那件事没法用桩证明，必须有真实 Key + 真实网络。
 * 本类把核对变成一条可重复执行的命令：
 *
 * <pre>
 * $env:MCP_IT = "true"
 * $env:MCP_ENABLED = "true"
 * $env:AMAP_MAPS_API_KEY = "<高德 Web服务 Key>"
 * mvn -B -Dtest=McpAmapLiveTest test
 * </pre>
 *
 * <p><b>断言口径</b>：只断言「不会被上游数据波动打脸」的事实（城市名、量级、字段存在），
 * 不断言具体公里数与 POI 名称 —— 那些会随高德数据每天变化。上游已知缺陷（如
 * {@code maps_geo} 丢弃 {@code city}）只打印不断言：vendor 修好之后测试不该变红。
 */
@EnabledIfEnvironmentVariable(named = "MCP_IT", matches = "true")
@SpringBootTest
@ActiveProfiles("test")
class McpAmapLiveTest {

    /** 12 个工具的远端名（与包内 tools/list 一致） */
    private static final Set<String> REMOTE_TOOLS = new TreeSet<>(List.of(
            "maps_geo", "maps_regeocode", "maps_direction_driving", "maps_direction_walking",
            "maps_bicycling", "maps_direction_transit_integrated", "maps_distance",
            "maps_text_search", "maps_around_search", "maps_search_detail",
            "maps_weather", "maps_ip_location"));

    /** 高德杭州西湖景区（GCJ-02）：路径与周边检索的固定基准点 */
    private static final String WEST_LAKE = "120.155070,30.274084";
    /** 杭州东站（GCJ-02） */
    private static final String HANGZHOU_EAST = "120.212600,30.290851";

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    @DisplayName("12 个 mcp_amap_* 工具全部注册，且远端名一个不差")
    void allTwelveToolsAreRegistered() {
        Set<String> registered = new TreeSet<>();
        for (String name : toolRegistry.all().keySet()) {
            if (name.startsWith("mcp_amap_")) {
                registered.add(name.substring("mcp_amap_".length()));
            }
        }
        assertThat(registered)
                .as("注册到 ToolRegistry 的 mcp_amap_* 工具（远端名）")
                .containsExactlyInAnyOrderElementsOf(REMOTE_TOOLS);
        for (String name : registered) {
            AgentTool tool = toolRegistry.get("mcp_amap_" + name);
            assertThat(tool.readOnly())
                    .as("MCP 工具 %s 必须只读，注册闸门才放行", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("逐个调用 12 个工具：真实输出非空、语义对得上已知事实")
    void everyToolReturnsMeaningfulBusinessOutput() {
        Map<String, String> raw = new LinkedHashMap<>();

        // ---- 地理编码：天安门是已知 POI，能反查出北京市东城区 ----
        String geo = call(raw, "maps_geo", Map.of("address", "天安门", "city", "北京"));
        assertThat(geo).contains("北京市").contains("东城区").contains("116.39");

        // ---- 逆地理编码：同一坐标反查回东城区（字段名 provice 是上游拼写错误，见报告） ----
        String regeo = call(raw, "maps_regeocode", Map.of("location", "116.397428,39.90923"));
        assertThat(regeo).contains("北京市").contains("东城区");

        // ---- 驾车：西湖 → 杭州东站，必须给出距离/耗时/导航步骤 ----
        String driving = call(raw, "maps_direction_driving",
                Map.of("origin", WEST_LAKE, "destination", HANGZHOU_EAST));
        assertThat(driving).contains("\"distance\"").contains("\"duration\"").contains("steps");
        assertThat(extractFirstNumber(driving, "distance"))
                .as("西湖到杭州东站驾车距离应在 5–20 km 量级")
                .isBetween(5_000L, 20_000L);

        // ---- 步行：西湖 → 雷峰塔方向，必须给出步骤且距离是步行量级 ----
        String walking = call(raw, "maps_direction_walking",
                Map.of("origin", WEST_LAKE, "destination", "120.148000,30.231000"));
        assertThat(extractFirstNumber(walking, "distance"))
                .as("西湖到雷峰塔步行距离应在 1–8 km 量级")
                .isBetween(1_000L, 8_000L);

        // ---- 公交：杭州东站 → 西湖，必须给出换乘方案（含起终点站名） ----
        String transit = call(raw, "maps_direction_transit_integrated", Map.of(
                "origin", HANGZHOU_EAST, "destination", WEST_LAKE, "city", "330100", "cityd", "330100"));
        assertThat(transit).contains("transits").contains("buslines");
        assertThat(transit).as("换乘方案必须落到具体线路名").contains("name");

        // ---- 骑行：v4 接口，返回 data.paths 结构 ----
        String bicycling = call(raw, "maps_bicycling",
                Map.of("origin", WEST_LAKE, "destination", "120.101000,30.240000"));
        assertThat(bicycling).contains("\"data\"").contains("paths");

        // ---- 测距：同一对坐标，直线(type=0) 必须小于驾车(type=1) ----
        String straight = call(raw, "maps_distance", Map.of(
                "origins", "116.397428,39.90923", "destination", "121.473701,31.230416", "type", "0"));
        String byRoad = call(raw, "maps_distance", Map.of(
                "origins", "116.397428,39.90923", "destination", "121.473701,31.230416", "type", "1"));
        long straightM = extractFirstNumber(straight, "distance");
        long roadM = extractFirstNumber(byRoad, "distance");
        assertThat(straightM)
                .as("北京天安门到上海人民广场的直线距离约 1060–1080 km，实测 %d m", straightM)
                .isBetween(1_000_000L, 1_150_000L);
        assertThat(roadM).as("驾车距离必须大于直线距离").isGreaterThan(straightM);

        // ---- 关键字检索：西湖 → 拿到 POI 列表与 id ----
        String textSearch = call(raw, "maps_text_search", Map.of("keywords", "西湖", "city", "杭州"));
        assertThat(textSearch).contains("pois").contains("B0");
        String poiId = extractPoiId(textSearch);

        // ---- 周边检索：西湖 1km 内的餐饮 ----
        String around = call(raw, "maps_around_search",
                Map.of("location", WEST_LAKE, "keywords", "餐厅", "radius", "1000"));
        assertThat(around).contains("pois").contains("typecode");

        // ---- POI 详情：用上一步真实取到的 id 反查，形成「检索 → 详情」闭环 ----
        String detail = call(raw, "maps_search_detail", Map.of("id", poiId));
        assertThat(detail).as("详情必须回传同一个 id 与名称").contains(poiId).contains("name");

        // ---- 天气：adcode 查询恒为预报（extensions=all），含未来数日 ----
        String weather = call(raw, "maps_weather", Map.of("city", "330100"));
        assertThat(weather).contains("杭州市").contains("forecasts").contains("dayweather");

        // ---- IP 定位：无结果时返回空数组而不是错误（见报告「空数组语义」） ----
        String ipLocation = call(raw, "maps_ip_location", Map.of("ip", "114.114.114.114"));
        assertThat(ipLocation).contains("province").contains("city");

        // 12 个工具全部走真实网络、全部拿到响应：把原始输出打到控制台留档
        assertThat(raw).hasSize(12);
        raw.forEach((tool, text) -> System.out.printf("%n[mcp-it] %-36s %5d 字符%n%s%n",
                tool, text.length(), abbreviate(text, 1200)));

        // 上游已知缺陷的现场证据：maps_geo 声明了 city 但请求里没带，跨城同名会命中别的城市
        String wrongCity = call(raw, "maps_geo", Map.of("address", "东方明珠", "city", "上海"));
        System.out.printf("%n[mcp-it] maps_geo(city=上海) 实际返回：%s%n", abbreviate(wrongCity, 400));
    }

    /** 调用一个已注册的 MCP 工具，顺带把原始输出收集起来留档 */
    private String call(Map<String, String> sink, String remoteName, Map<String, Object> args) {
        AgentTool tool = toolRegistry.get("mcp_amap_" + remoteName);
        assertThat(tool).as("工具未注册: %s", remoteName).isNotNull();
        Object result = tool.execute(new LinkedHashMap<>(args));
        assertThat(result).as("工具 %s 应返回文本结果", remoteName).isInstanceOf(String.class);
        String text = ((String) result).trim();
        assertThat(text).as("工具 %s 返回空结果", remoteName).isNotBlank();
        sink.put(remoteName, text);
        return text;
    }

    /** 取文本里第一个 {@code "字段":数字} 的数字（MCP 返回的是 JSON 文本） */
    private static long extractFirstNumber(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"?(\\d+)")
                .matcher(json);
        assertThat(m.find()).as("响应里找不到字段 %s: %s", field, abbreviate(json, 200)).isTrue();
        return Long.parseLong(m.group(1));
    }

    /** 从检索结果里取第一个 POI id（形如 B0H215866V） */
    private static String extractPoiId(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"id\"\\s*:\\s*\"(B[0-9A-Z]{8,})\"")
                .matcher(json);
        assertThat(m.find()).as("检索结果里没有 POI id: %s", abbreviate(json, 200)).isTrue();
        return m.group(1);
    }

    private static String abbreviate(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}