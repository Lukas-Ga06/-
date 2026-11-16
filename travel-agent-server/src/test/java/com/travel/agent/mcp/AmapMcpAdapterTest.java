package com.travel.agent.mcp;

import com.travel.agent.mcp.AmapMcpAdapter.AmapTool;
import com.travel.agent.mcp.AmapMcpAdapter.Capability;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高德适配层单测：工具名 → 语义名映射、从 ToolRegistry 发现已注册的高德工具、生成提示词摘要。
 * 全部用假工具，不需要网络，也不需要高德 Key。
 */
class AmapMcpAdapterTest {

    private final AmapMcpAdapter adapter = new AmapMcpAdapter();

    /** 造一个只用于注册表的假工具（名字/描述即可，不执行） */
    private static AgentTool fakeTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public RiskLevel getRiskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public Object execute(Map<String, Object> args) {
                return null;
            }
        };
    }

    @Test
    void mapsRealAmapToolNamesToSemanticCapabilities() {
        // 以下 12 个工具名来自真实包 @amap/amap-maps-mcp-server 0.0.8 的 build/index.js（tools/list 实测一致）
        assertEquals(Optional.of(Capability.CITY_GEOCODE), adapter.capabilityOf("maps_geo"));
        assertEquals(Optional.of(Capability.REGEOCODE), adapter.capabilityOf("maps_regeocode"));
        assertEquals(Optional.of(Capability.ROUTE_DRIVING), adapter.capabilityOf("maps_direction_driving"));
        assertEquals(Optional.of(Capability.ROUTE_WALKING), adapter.capabilityOf("maps_direction_walking"));
        assertEquals(Optional.of(Capability.ROUTE_TRANSIT),
                adapter.capabilityOf("maps_direction_transit_integrated"));
        assertEquals(Optional.of(Capability.ROUTE_BICYCLING), adapter.capabilityOf("maps_bicycling"));
        assertEquals(Optional.of(Capability.MEASURE_DISTANCE), adapter.capabilityOf("maps_distance"));
        assertEquals(Optional.of(Capability.POI_TEXT_SEARCH), adapter.capabilityOf("maps_text_search"));
        assertEquals(Optional.of(Capability.POI_AROUND_SEARCH), adapter.capabilityOf("maps_around_search"));
        assertEquals(Optional.of(Capability.POI_DETAIL), adapter.capabilityOf("maps_search_detail"));
        assertEquals(Optional.of(Capability.WEATHER), adapter.capabilityOf("maps_weather"));
        assertEquals(Optional.of(Capability.IP_LOCATION), adapter.capabilityOf("maps_ip_location"));
        // 非高德工具 / 空值不映射
        assertTrue(adapter.capabilityOf("web_search").isEmpty());
        assertTrue(adapter.capabilityOf(null).isEmpty());
        assertEquals("route_transit", Capability.ROUTE_TRANSIT.semanticName());
    }

    @Test
    void discoversRegisteredAmapToolsAndBuildsPromptSummary() {
        ToolRegistry registry = new ToolRegistry(List.of(
                // 描述带桥接层注入的参数提示（含必填 * 与参数说明）
                fakeTool("mcp_amap_maps_geo",
                        "(MCP:amap) 将详细的结构化地址转换为经纬度坐标（参数: address*(待解析的结构化地址信息), city(指定查询的城市)；*为必填）"),
                // 描述里没有参数提示 → 回退到内置真实 schema（4 个必填参数）
                fakeTool("mcp_amap_maps_direction_transit_integrated", "(MCP:amap) 公交路径规划"),
                // 非高德 MCP 工具、内置工具都不应被当成高德工具
                fakeTool("mcp_wiki_web_search", "(MCP:wiki) 搜索网页（参数: query*；*为必填）"),
                fakeTool("web_search", "内置搜索")));

        List<AmapTool> tools = adapter.discover(registry);
        assertEquals(2, tools.size(), "只应发现高德工具，实际: " + tools);
        assertTrue(adapter.isAvailable(registry));

        AmapTool geo = tools.get(0);
        assertEquals(Capability.CITY_GEOCODE, geo.capability());
        assertEquals("mcp_amap_maps_geo", geo.registryName());
        assertEquals("maps_geo", geo.remoteName());
        assertEquals(List.of("address", "city"), geo.params());
        assertEquals(List.of("address"), geo.required());

        AmapTool transit = tools.get(1);
        assertEquals(Capability.ROUTE_TRANSIT, transit.capability());
        assertEquals(List.of("origin", "destination", "city", "cityd"), transit.params());
        assertEquals(List.of("origin", "destination", "city", "cityd"), transit.required());

        String prompt = adapter.promptSummary(registry);
        assertTrue(prompt.contains("city_geocode"), prompt);
        assertTrue(prompt.contains("mcp_amap_maps_geo"), prompt);
        assertTrue(prompt.contains("address*"), prompt);
        assertTrue(prompt.contains("route_transit"), prompt);
        assertTrue(prompt.contains("mcp_amap_maps_direction_transit_integrated"), prompt);
        assertTrue(prompt.contains("*为必填"), prompt);
        assertFalse(prompt.contains("web_search"), "非高德工具不应出现在高德提示里: " + prompt);

        assertEquals(Optional.of("mcp_amap_maps_direction_transit_integrated"),
                adapter.registryName(registry, Capability.ROUTE_TRANSIT));
        assertTrue(adapter.registryName(registry, Capability.WEATHER).isEmpty());
    }

    @Test
    void withoutAmapServerDiscoveryIsEmptyAndHarmless() {
        ToolRegistry registry = new ToolRegistry(List.of(fakeTool("web_search", "内置搜索")));
        assertTrue(adapter.discover(registry).isEmpty());
        assertFalse(adapter.isAvailable(registry));
        assertEquals("", adapter.promptSummary(registry), "未接入高德时不应往提示词里塞任何东西");
        assertEquals("", adapter.promptSummary(null));
        assertTrue(adapter.discover(null).isEmpty());
    }

    @Test
    void paramHintParserHandlesDescriptionsAndMissingHint() {
        AmapMcpAdapter.ParamSummary parsed = AmapMcpAdapter.parseParamHint(
                "(MCP:amap) x（参数: origin*(起点经纬度), destination, city；*为必填）");
        assertEquals(List.of("origin", "destination", "city"), parsed.params());
        assertEquals(List.of("origin"), parsed.required());

        AmapMcpAdapter.ParamSummary none = AmapMcpAdapter.parseParamHint("(MCP:amap) 没有参数提示");
        assertTrue(none.params().isEmpty());
        assertTrue(none.required().isEmpty());
        assertTrue(AmapMcpAdapter.parseParamHint(null).params().isEmpty());
    }
}