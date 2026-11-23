package com.travel.agent.tool;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.service.RouteService;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.impl.CalculateBudgetTool;
import com.travel.agent.tool.impl.CalculateRouteTool;
import com.travel.agent.tool.impl.GetItineraryTool;
import com.travel.agent.tool.impl.OnlineCitySearchTool;
import com.travel.agent.tool.impl.QueryTravelKnowledgeTool;
import com.travel.agent.tool.impl.QueryWeatherTool;
import com.travel.agent.tool.impl.SearchAttractionTool;
import com.travel.agent.tool.impl.SearchHotelTool;
import com.travel.agent.tool.impl.SearchRestaurantTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具层契约：只允许只读工具注册，写操作必须走 AgentPlan → 确认 → commit。
 * 回归背景：此前 add/modify/undo 三个写工具既绕过确认与撤销历史，又缺少归属校验。
 *
 * <p>P0-B 追加：内置工具必须有与 MCP 一致的结构化参数声明；ToolInvoker 在 execute 前
 * 拒绝未知参数/缺失必填参数，并落 tool_call_trace；数字以字符串形式给出时内置工具仍能安全解析。
 */
class ToolLayerTest {

    private static AgentTool tool(String name, boolean readOnly) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public RiskLevel getRiskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public boolean readOnly() {
                return readOnly;
            }

            @Override
            public Object execute(Map<String, Object> args) {
                return null;
            }
        };
    }

    /** P0-B：声明 city 必填 + nearLat 可选的测试工具，用来验证 ToolInvoker 的参数门禁 */
    private static AgentTool schemaTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return "schema_tool";
            }

            @Override
            public String getDescription() {
                return "带参数声明的测试工具";
            }

            @Override
            public RiskLevel getRiskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public List<ToolParameter> getParameters() {
                return List.of(
                        new ToolParameter("city", true, "string", "城市"),
                        new ToolParameter("nearLat", false, "number", "数字"));
            }

            @Override
            public Object execute(Map<String, Object> args) {
                return args.get("city");
            }
        };
    }

    private static AgentContext context() {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage("测试");
        return ctx;
    }

    private static ToolInvoker invoker(AgentTool tool, AgentTraceService traceService) {
        return new ToolInvoker(new ToolRegistry(List.of(tool)), new SafetyInterceptor(), traceService);
    }

    @Test
    @DisplayName("只读工具正常注册")
    void registersReadOnlyTools() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("search_attraction", true)));

        assertThat(registry.all()).containsKey("search_attraction");
    }

    @Test
    @DisplayName("会写数据的工具被拒绝注册")
    void rejectsMutatingTools() {
        ToolRegistry registry = new ToolRegistry(List.of(
                tool("search_attraction", true),
                tool("add_itinerary_item", false)));

        assertThat(registry.all()).containsOnlyKeys("search_attraction");
    }

    @Test
    @DisplayName("工具审计不再抛异常（HIGH 由方案层表达，不再伪装成系统错误）")
    void safetyCheckOnlyAudits() {
        SafetyInterceptor interceptor = new SafetyInterceptor();

        assertThatCode(() -> interceptor.check("demo", tool("search_hotel", true), Map.of("city", "上海")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("风险评估：只读 LOW / 普通修改 MEDIUM / 超预算 HIGH")
    void evaluatePlanChangeLevels() {
        SafetyInterceptor interceptor = new SafetyInterceptor();
        java.math.BigDecimal before = java.math.BigDecimal.valueOf(1000);
        java.math.BigDecimal over = java.math.BigDecimal.valueOf(4000);
        java.math.BigDecimal budget = java.math.BigDecimal.valueOf(3000);

        assertThat(interceptor.evaluatePlanChange(true, before, before, budget)).isEqualTo(RiskLevel.LOW);
        assertThat(interceptor.evaluatePlanChange(false, before, java.math.BigDecimal.valueOf(2000), budget))
                .isEqualTo(RiskLevel.MEDIUM);
        assertThat(interceptor.evaluatePlanChange(false, before, over, budget)).isEqualTo(RiskLevel.HIGH);
    }

    // ==================== P0-B：内置工具参数 schema ====================

    private static void assertParams(AgentTool tool, String... names) {
        assertThat(tool.getParameters()).extracting(AgentTool.ToolParameter::name).containsExactly(names);
        assertThat(tool.getParameters()).allSatisfy(parameter -> {
            assertThat(parameter.type()).isNotBlank();
            assertThat(parameter.description()).isNotBlank();
        });
    }

    @Test
    @DisplayName("P0-B：9 个内置工具按 execute 真实读取的键声明参数 schema，不再让模型猜参数名")
    void builtinToolsDeclareAccurateParameters() {
        assertParams(new SearchAttractionTool(null), "city", "venue", "nearLat", "nearLng", "exclude");
        assertParams(new SearchHotelTool(null), "city", "preferSubway", "maxPrice");
        assertParams(new SearchRestaurantTool(null), "city", "nearLat", "nearLng");
        assertParams(new CalculateRouteTool(null), "fromLat", "fromLng", "toLat", "toLng");
        assertParams(new QueryWeatherTool(null), "city", "date", "mentionRain");
        assertParams(new GetItineraryTool(null), "tripId");
        assertParams(new CalculateBudgetTool(null, null), "tripId");
        assertParams(new QueryTravelKnowledgeTool(null), "query", "city", "topK", "userId");
        assertParams(new OnlineCitySearchTool(null), "city");

        // 必填标记必须与 execute 的真实依赖一致：城市/坐标/行程 ID/检索词是硬依赖
        assertThat(new SearchAttractionTool(null).getParameters())
                .filteredOn(AgentTool.ToolParameter::required)
                .extracting(AgentTool.ToolParameter::name)
                .containsExactly("city");
        assertThat(new CalculateRouteTool(null).getParameters())
                .filteredOn(AgentTool.ToolParameter::required)
                .hasSize(4);
        assertThat(new QueryTravelKnowledgeTool(null).getParameters())
                .filteredOn(AgentTool.ToolParameter::required)
                .extracting(AgentTool.ToolParameter::name)
                .containsExactly("query");
    }

    @Test
    @DisplayName("P0-B：未知参数被拒绝，错误信息列出可用参数与必填项，并落一条失败追踪")
    void rejectsUnknownParameterWithActionableMessage() {
        AgentTraceService trace = mock(AgentTraceService.class);
        ToolInvoker invoker = invoker(schemaTool(), trace);

        assertThatThrownBy(() -> invoker.invoke(context(), "schema_tool", Map.of("location", "杭州")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知参数 location")
                .hasMessageContaining("可用参数：city、nearLat")
                .hasMessageContaining("必填：city");
        verify(trace).recordToolCall(any(), any(), any(), eq("schema_tool"), any(), isNull(),
                eq(false), contains("未知参数 location"), anyLong());
    }

    @Test
    @DisplayName("P0-B：缺少必填参数被拒绝，错误信息点名缺哪个参数，并落一条失败追踪")
    void rejectsMissingRequiredParameter() {
        AgentTraceService trace = mock(AgentTraceService.class);
        ToolInvoker invoker = invoker(schemaTool(), trace);

        assertThatThrownBy(() -> invoker.invoke(context(), "schema_tool", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必填参数 city");
        verify(trace).recordToolCall(any(), any(), any(), eq("schema_tool"), any(), isNull(),
                eq(false), contains("缺少必填参数 city"), anyLong());
    }

    @Test
    @DisplayName("P0-B：合法参数通过校验并正常执行（成功追踪照旧记录）")
    void acceptsValidArguments() {
        AgentTraceService trace = mock(AgentTraceService.class);
        ToolInvoker invoker = invoker(schemaTool(), trace);

        Object result = invoker.invoke(context(), "schema_tool", Map.of("city", "上海", "nearLat", 31.2));

        assertThat(result).isEqualTo("上海");
        verify(trace).recordToolCall(any(), any(), any(), eq("schema_tool"), any(), eq("上海"),
                eq(true), isNull(), anyLong());
    }

    @Test
    @DisplayName("P0-B：模型把数字写成字符串时内置工具安全解析（不再 ClassCastException）")
    void stringNumbersAreAcceptedByBuiltinTools() {
        RouteService routeService = mock(RouteService.class);
        when(routeService.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new RouteInfo(1.2, 15, 5, 12.0, "estimated"));
        ToolInvoker invoker = invoker(new CalculateRouteTool(routeService), mock(AgentTraceService.class));

        Object result = invoker.invoke(context(), "calculate_route", Map.of(
                "fromLat", "31.2", "fromLng", "121.4", "toLat", "31.3", "toLng", "121.5"));

        assertThat(result).isInstanceOf(RouteInfo.class);
        verify(routeService).route(31.2, 121.4, 31.3, 121.5);
    }

    @Test
    @DisplayName("P0-B：exclude 同时接受数组与逗号分隔串，数字字符串坐标可解析")
    void attractionToolAcceptsListAndStringNumbers() {
        TravelCatalogService catalog = mock(TravelCatalogService.class);
        CityCatalog city = mock(CityCatalog.class);
        when(catalog.requireCity("上海")).thenReturn(city);
        when(catalog.searchAttractions(eq(city), isNull(), eq(31.2), eq(121.4),
                eq(List.of("外滩", "豫园")), eq(6))).thenReturn(List.of());
        SearchAttractionTool tool = new SearchAttractionTool(catalog);

        Object fromList = tool.execute(Map.of("city", "上海", "nearLat", "31.2", "nearLng", "121.4",
                "exclude", List.of("外滩", "豫园")));
        Object fromText = tool.execute(Map.of("city", "上海", "nearLat", 31.2, "nearLng", 121.4,
                "exclude", "外滩,豫园"));

        assertThat(fromList).isEqualTo(List.of());
        assertThat(fromText).isEqualTo(List.of());
        verify(catalog, org.mockito.Mockito.times(2)).searchAttractions(eq(city), isNull(),
                eq(31.2), eq(121.4), eq(List.of("外滩", "豫园")), eq(6));
    }

    @Test
    @DisplayName("P0-B：提示词写「室内/室外」，工具接受中文并归一化成目录层的 indoor/outdoor")
    void attractionToolNormalizesChineseVenue() {
        TravelCatalogService catalog = mock(TravelCatalogService.class);
        CityCatalog city = mock(CityCatalog.class);
        when(catalog.requireCity("上海")).thenReturn(city);
        when(catalog.searchAttractions(eq(city), eq("indoor"), isNull(), isNull(),
                eq(List.of()), eq(6))).thenReturn(List.of());
        SearchAttractionTool tool = new SearchAttractionTool(catalog);

        tool.execute(Map.of("city", "上海", "venue", "室内"));

        verify(catalog).searchAttractions(eq(city), eq("indoor"), isNull(), isNull(), eq(List.of()), eq(6));
    }

    @Test
    @DisplayName("P0-B：没有声明参数的工具保持宽松（MCP 工具不误伤上游 schema 未声明字段）")
    void toolsWithoutSchemaRemainLenient() {
        AgentTraceService trace = mock(AgentTraceService.class);
        ToolInvoker invoker = invoker(tool("mcp_amap_maps_geo", true), trace);

        assertThatCode(() -> invoker.invoke(context(), "mcp_amap_maps_geo", Map.of("anything", "ok")))
                .doesNotThrowAnyException();
    }
}
