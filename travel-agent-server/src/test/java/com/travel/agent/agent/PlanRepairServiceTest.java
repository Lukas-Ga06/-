package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.config.WeatherProperties;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.model.WeatherLookup;
import com.travel.agent.service.RouteService;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.service.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.2 FR-06：确定性修复器——审核发现的问题必须能被「改计划」真正解决，且改法可解释。
 *
 * <p>纯单测：不需要数据库（计划只有 ADD 操作），天气用可控子类替换，
 * 路线走 {@link RouteService} 的本地估算口径（无 Key 时与生产降级路径一致）。
 */
class PlanRepairServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 20);

    private TravelCatalogService catalog;
    private RouteService routeService;
    private PlanRepairService repairService;
    private CityCatalog shanghai;

    /** 可控天气：真实预报 + 固定小雨，让天气修复路径可测（不依赖模拟种子） */
    private static WeatherService rainyWeather() {
        return new WeatherService(new WeatherProperties(), null) {
            @Override
            public WeatherLookup forecast(String city, LocalDate date) {
                return WeatherLookup.available(
                        new WeatherInfo(city, date, "小雨", 85, 12, 18, WeatherInfo.SOURCE_REAL));
            }
        };
    }

    @BeforeEach
    void setUp() {
        routeService = new RouteService();
        catalog = new TravelCatalogService(new ObjectMapper(), routeService);
        catalog.load();
        shanghai = catalog.matchCity("上海");
        PlanInspector inspector = new PlanInspector(null, routeService, rainyWeather());
        repairService = new PlanRepairService(inspector, routeService);
    }

    private static ItineraryItem attraction(int day, String title, String start, String end,
                                            String tags, Double lat, Double lng, int cost) {
        return ItineraryItem.builder()
                .dayIndex(day)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title(title)
                .startTime(START.plusDays(day - 1L).atTime(LocalTime.parse(start)))
                .endTime(START.plusDays(day - 1L).atTime(LocalTime.parse(end)))
                .latitude(lat)
                .longitude(lng)
                .cost(BigDecimal.valueOf(cost))
                .tags(tags)
                .build();
    }

    private AgentPlan plan(int budget, List<ItineraryItem> drafts) {
        List<PlanOperation> operations = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ItineraryItem draft : drafts) {
            operations.add(PlanOperation.builder().type(PlanOperation.ADD).draft(draft)
                    .detail("新增：" + draft.getTitle()).build());
            total = total.add(draft.getCost() == null ? BigDecimal.ZERO : draft.getCost());
        }
        Map<String, Object> extras = new HashMap<>();
        extras.put("total", total);
        return AgentPlan.builder()
                .planId("plan-repair")
                .intent(IntentResult.PLAN_TRIP)
                .city(shanghai)
                .tripDraft(TripDraft.builder()
                        .name("修复测试").destination("上海")
                        .startDate(START).days(3).budget(BigDecimal.valueOf(budget))
                        .build())
                .operations(operations)
                .extras(extras)
                .build();
    }

    private static ItineraryItem onlyDraft(AgentPlan plan) {
        return plan.getOperations().get(0).getDraft();
    }

    @Test
    @DisplayName("雨天户外景点被换成室内景点（真实预报），修复动作可追溯")
    void rainyOutdoorSwappedForIndoor() {
        AgentPlan plan = plan(3000, List.of(
                attraction(1, "外滩", "10:00", "11:30", "室外,地标", 31.2397, 121.4906, 0)));

        PlanRepairService.Result result = repairService.repair(plan, null);

        assertThat(result.changed()).isTrue();
        ItineraryItem repaired = onlyDraft(plan);
        assertThat(repaired.getTitle()).isNotEqualTo("外滩");
        assertThat(repaired.isIndoor()).isTrue();
        assertThat(result.actions()).anyMatch(action -> action.contains("外滩") && action.contains("室内景点"));

        // 复审：没有户外景点了，天气硬约束不再触发（室内景点耗时照常可安排）
        PlanInspector inspector = new PlanInspector(null, routeService, rainyWeather());
        ReflectAgent.ReviewVerdict verdict = new ReflectAgent(inspector).reviewVerdict(plan, null);
        assertThat(verdict.issues()).noneMatch(issue ->
                ReflectAgent.PlanIssue.TYPE_WEATHER_OUTDOOR.equals(issue.type()));
    }

    @Test
    @DisplayName("转场来不及时把后段整体后移，补足驾车时长 + 5 分钟缓冲")
    void transferShortageIsFixedByShiftingLaterItems() {
        ItineraryItem first = attraction(1, "外滩", "10:00", "11:00", "室内", 31.23, 121.47, 0);
        ItineraryItem second = attraction(1, "远郊景点", "11:10", "12:10", "室内", 31.40, 121.60, 50);
        AgentPlan plan = plan(3000, List.of(first, second));

        RouteInfo route = routeService.route(first.getLatitude(), first.getLongitude(),
                second.getLatitude(), second.getLongitude());
        LocalDateTime expectedStart = first.getEndTime().plusMinutes(route.driveMinutes() + 5L);

        PlanRepairService.Result result = repairService.repair(plan, null);

        assertThat(result.changed()).isTrue();
        assertThat(result.actions()).anyMatch(action -> action.contains("后移"));
        assertThat(second.getStartTime()).isEqualTo(expectedStart);
        assertThat(second.getEndTime()).isEqualTo(expectedStart.plusHours(1));
    }

    @Test
    @DisplayName("预算超限：移除最贵的可移除景点，每天保底 1 个，合计写回 extras.total")
    void budgetOverrunTrimsHeaviestRemovableAttraction() {
        AgentPlan plan = plan(400, List.of(
                attraction(1, "甲", "09:00", "10:00", "室内", null, null, 100),
                attraction(1, "乙", "11:00", "12:00", "室内", null, null, 200),
                attraction(2, "丙", "09:00", "10:00", "室内", null, null, 300)));

        PlanRepairService.Result result = repairService.repair(plan, null);

        assertThat(result.changed()).isTrue();
        // 丙是第 2 天唯一景点（保底不动）；乙是「当天景点数 > 1」里最贵的
        assertThat(plan.getOperations())
                .extracting(op -> op.getDraft().getTitle())
                .containsExactly("甲", "丙");
        assertThat(plan.getExtras().get("total")).isEqualTo(BigDecimal.valueOf(400));
        assertThat(result.actions()).anyMatch(action -> action.contains("乙") && action.contains("预算"));
    }

    @Test
    @DisplayName("天气修复换上的室内景点不会被预算精简再删掉（两处修复不互相抵消）")
    void weatherSwapSurvivesBudgetTrim() {
        AgentPlan plan = plan(150, List.of(
                attraction(1, "外滩", "10:00", "11:30", "室外,地标", 31.2397, 121.4906, 0),
                attraction(1, "南京路步行街", "14:00", "15:30", "室外,购物", 31.2359, 121.4802, 100)));

        PlanRepairService.Result result = repairService.repair(plan, null);

        // 外滩最近的室内景点是东方明珠（票价 199），天气修复先换入；预算仍超时只能删「南京路步行街」
        assertThat(plan.getOperations())
                .extracting(op -> op.getDraft().getTitle())
                .contains("东方明珠")
                .doesNotContain("南京路步行街");
        assertThat(result.actions()).noneMatch(action -> action.contains("移除「东方明珠」"));
    }
}