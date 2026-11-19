package com.travel.agent.agent;

import com.travel.agent.llm.LlmProperties;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Restaurant;
import com.travel.agent.service.BudgetService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.ToolInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P1：plan 阶段的预算降级（研究循环与 LLM 结构化规划都先看剩余预算）。
 *
 * <p>关键不变量：预算耗尽时<b>不调用</b>研究循环与结构化规划，但规则规划照常产出可确认的方案——
 * 降级不等于「什么都不返回」，而是「用确定性的规则路径按时返回，并如实标注降级」。
 */
class PlannerAgentBudgetTest {

    private final CityCatalog city = new CityCatalog(
            "测试市",
            List.of(
                    new Attraction("a1", "故宫", "outdoor", List.of("历史"), 39.9, 116.4, 60, 2, 4.8, "明清皇宫"),
                    new Attraction("a2", "科技馆", "indoor", List.of("亲子"), 39.8, 116.5, 30, 2.5, 4.6, "科技展览")),
            List.of(),
            List.of(new Restaurant("r1", "老字号烤鸭", "京菜", 120, 4.6, 39.91, 116.41, "烤鸭")));

    private LlmProperties usableLlmProperties() {
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        props.setApiKey("test-key");
        return props;
    }

    private IntentResult planIntent() {
        return IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .constraints(Map.of())
                .build();
    }

    private AgentContext context(long budgetMs) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage("帮我规划测试市三日游，2人，预算3000元");
        ctx.setBudget(AgentBudget.start(budgetMs, 30_000));
        return ctx;
    }

    /** 规则路径需要的工具桩：候选清单直接回真实目录数据（与线上工具返回同构） */
    private ToolInvoker tools() {
        ToolInvoker tools = mock(ToolInvoker.class);
        when(tools.invoke(any(), eq("search_attraction"), any())).thenAnswer(invocation -> city.attractions());
        when(tools.invoke(any(), eq("search_restaurant"), any())).thenAnswer(invocation -> city.restaurants());
        return tools;
    }

    private PlannerAgent planner(ToolInvoker tools, TravelCatalogService catalog, StructuredPlanner structured,
                                 AgentToolLoop toolLoop) {
        BudgetService budgetService = mock(BudgetService.class);
        when(budgetService.totalOf(any())).thenReturn(BigDecimal.valueOf(500));
        return new PlannerAgent(tools, catalog, mock(ItineraryService.class), budgetService,
                usableLlmProperties(), structured, new PlannerDraftSupport(tools), toolLoop,
                new AgentTaskRecorder(mock(com.travel.agent.service.AgentTraceService.class)));
    }

    @Test
    @DisplayName("预算耗尽：不跑研究循环/结构化规划，规则规划照常产出方案，并记录 research/plan 降级")
    void exhaustedBudgetSkipsLlmStagesButStillProducesPlan() {
        ToolInvoker tools = tools();
        TravelCatalogService catalog = mock(TravelCatalogService.class);
        when(catalog.matchCity(anyString())).thenReturn(city);
        StructuredPlanner structured = mock(StructuredPlanner.class);
        AgentToolLoop toolLoop = mock(AgentToolLoop.class);
        AgentContext ctx = context(0L);

        AgentPlan plan = planner(tools, catalog, structured, toolLoop).plan(ctx, planIntent(), null);

        assertThat(plan).isNotNull();
        assertThat(plan.getOperations()).as("降级不等于不返回：规则规划仍要产出可确认的操作").isNotEmpty();
        verifyNoInteractions(structured);
        verifyNoInteractions(toolLoop);
        assertThat(ctx.getBudgetDegradations())
                .extracting(AgentContext.BudgetDegradation::stage)
                .contains(AgentBudget.STAGE_RESEARCH, AgentBudget.STAGE_PLAN);
        assertThat(ctx.getBudgetDegradations())
                .allSatisfy(d -> assertThat(d.detail()).contains("预算"));
    }

    @Test
    @DisplayName("预算充足：研究循环与结构化规划照常调用，不产生降级")
    void sufficientBudgetCallsLlmStages() {
        ToolInvoker tools = tools();
        TravelCatalogService catalog = mock(TravelCatalogService.class);
        when(catalog.matchCity(anyString())).thenReturn(city);
        StructuredPlanner structured = mock(StructuredPlanner.class);
        AgentToolLoop toolLoop = mock(AgentToolLoop.class);
        when(toolLoop.run(any(), anyString(), any(), anyInt()))
                .thenReturn(new AgentToolLoop.LoopResult(false, "测试跳过研究", List.of()));
        AgentContext ctx = context(30_000L);

        AgentPlan plan = planner(tools, catalog, structured, toolLoop).plan(ctx, planIntent(), null);

        assertThat(plan).isNotNull();
        assertThat(ctx.hasBudgetDegradations()).isFalse();
        verify(toolLoop).run(any(), anyString(), any(), anyInt());
        verify(structured).planNewTrip(any(), any(), anyString(), any(), any(), any(AgentBudget.class));
    }
}