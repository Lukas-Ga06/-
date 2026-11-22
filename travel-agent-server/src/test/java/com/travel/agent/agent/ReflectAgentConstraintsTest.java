package com.travel.agent.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.service.RouteService;
import com.travel.agent.service.WeatherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.2 FR-06：审核新增两类硬约束（转场时间不够、雨天排户外）的行为。
 *
 * <p>纯单测（不起 Spring）：计划只有 ADD 操作时审核不需要数据库，
 * 天气用可控的 {@link WeatherService} 子类替换，避免测试依赖真实预报或模拟种子。
 */
class ReflectAgentConstraintsTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 20);

    /** 可控天气：固定返回给定预报；real=true 时把数据标成「真实预报」来源 */
    private static WeatherService weather(WeatherInfo info, boolean real) {
        WeatherInfo payload = real
                ? new WeatherInfo(info.city(), info.date(), info.condition(), info.rainProbability(),
                        info.tempLow(), info.tempHigh(), WeatherInfo.SOURCE_REAL)
                : info;
        return new WeatherService(new com.travel.agent.config.WeatherProperties(), null) {
            @Override
            public WeatherInfo forecast(String city, LocalDate date, boolean mentionRain) {
                return payload;
            }
        };
    }

    private static final WeatherInfo SUNNY = new WeatherInfo("上海", START, "晴", 5, 15, 24);
    private static final WeatherInfo RAINY = new WeatherInfo("上海", START, "小雨", 80, 12, 18);

    private static ReflectAgent agent(WeatherInfo weather, boolean real) {
        RouteService routeService = new RouteService();
        PlanInspector inspector = new PlanInspector(null, routeService, weather(weather, real));
        return new ReflectAgent(inspector);
    }

    private static ItineraryItem attraction(int day, String title, String start, String end,
                                            double lat, double lng, int cost) {
        return ItineraryItem.builder()
                .dayIndex(day)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title(title)
                .startTime(START.plusDays(day - 1L).atTime(LocalTime.parse(start)))
                .endTime(START.plusDays(day - 1L).atTime(LocalTime.parse(end)))
                .latitude(lat)
                .longitude(lng)
                .cost(BigDecimal.valueOf(cost))
                .tags("室外,地标")
                .build();
    }

    private static AgentPlan plan(int budget, List<ItineraryItem> drafts) {
        List<PlanOperation> operations = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ItineraryItem draft : drafts) {
            operations.add(PlanOperation.builder().type(PlanOperation.ADD).draft(draft)
                    .detail("新增：" + draft.getTitle()).build());
            total = total.add(draft.getCost() == null ? BigDecimal.ZERO : draft.getCost());
        }
        Map<String, Object> extras = new HashMap<>();
        extras.put("total", total);
        extras.put("days", 1);
        return AgentPlan.builder()
                .planId("plan-constraints")
                .intent(IntentResult.PLAN_TRIP)
                .city(new com.travel.agent.model.CityCatalog("上海", List.of(), List.of(), List.of()))
                .tripDraft(TripDraft.builder()
                        .name("审核约束测试").destination("上海")
                        .startDate(START).days(1).budget(BigDecimal.valueOf(budget))
                        .build())
                .operations(operations)
                .extras(extras)
                .build();
    }

    @Test
    @DisplayName("转场留的时间连路上都不够 → TRANSFER_TIME（HIGH，不通过）")
    void transferTooShortIsHardViolation() {
        // 两景点相距约 20km（驾车约 50 分钟），中间只留 10 分钟
        AgentPlan plan = plan(3000, List.of(
                attraction(1, "外滩", "10:00", "11:00", 31.23, 121.47, 0),
                attraction(1, "远郊景点", "11:10", "12:10", 31.40, 121.60, 50)));

        ReflectAgent.ReviewVerdict verdict = agent(SUNNY, false).reviewVerdict(plan, null);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.issues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(ReflectAgent.PlanIssue.TYPE_TRANSFER_TIME);
            assertThat(issue.severity()).isEqualTo(ReflectAgent.PlanIssue.SEVERITY_HIGH);
            assertThat(issue.message()).contains("转场").contains("只留了 10 分钟");
        });
        assertThat(verdict.warnings()).anyMatch(w -> w.contains("转场"));
    }

    @Test
    @DisplayName("同一段转场留出足够时间 → 不报 TRANSFER_TIME（不误伤正常行程）")
    void transferWithEnoughTimePasses() {
        AgentPlan plan = plan(3000, List.of(
                attraction(1, "外滩", "10:00", "11:00", 31.23, 121.47, 0),
                attraction(1, "远郊景点", "14:00", "15:00", 31.40, 121.60, 50)));

        ReflectAgent.ReviewVerdict verdict = agent(SUNNY, false).reviewVerdict(plan, null);

        assertThat(verdict.issues()).noneMatch(i ->
                ReflectAgent.PlanIssue.TYPE_TRANSFER_TIME.equals(i.type()));
        assertThat(verdict.passed()).isTrue();
    }

    @Test
    @DisplayName("真实预报有雨且排了户外 → WEATHER_OUTDOOR（HIGH，不通过）")
    void rainyOutdoorIsHardViolationForRealForecast() {
        AgentPlan plan = plan(3000, List.of(
                attraction(1, "外滩", "10:00", "11:30", 31.23, 121.47, 0)));

        ReflectAgent.ReviewVerdict verdict = agent(RAINY, true).reviewVerdict(plan, null);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.issues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(ReflectAgent.PlanIssue.TYPE_WEATHER_OUTDOOR);
            assertThat(issue.severity()).isEqualTo(ReflectAgent.PlanIssue.SEVERITY_HIGH);
            assertThat(issue.message()).contains("外滩");
        });
    }

    @Test
    @DisplayName("模拟天气有雨只给 MEDIUM 提示：不能拿假数据强制改行程（ADR-006）")
    void simulatedRainIsOnlyAdvisory() {
        AgentPlan plan = plan(3000, List.of(
                attraction(1, "外滩", "10:00", "11:30", 31.23, 121.47, 0)));

        ReflectAgent.ReviewVerdict verdict = agent(RAINY, false).reviewVerdict(plan, null);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.issues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(ReflectAgent.PlanIssue.TYPE_WEATHER_OUTDOOR);
            assertThat(issue.severity()).isEqualTo(ReflectAgent.PlanIssue.SEVERITY_MEDIUM);
            assertThat(issue.message()).contains("模拟天气");
        });
    }

    @Test
    @DisplayName("局部修改（MODIFY_ITINERARY）不拿整条既有行程判硬约束，避免历史紧排班阻止合理修改")
    void modificationIsNotBlockedByWholeItineraryConstraints() {
        AgentPlan plan = plan(3000, List.of(
                attraction(1, "外滩", "10:00", "11:00", 31.23, 121.47, 0),
                attraction(1, "远郊景点", "11:10", "12:10", 31.40, 121.60, 50)));
        AgentPlan modification = plan.toBuilder().intent(IntentResult.MODIFY_ITINERARY).build();

        ReflectAgent.ReviewVerdict verdict = agent(SUNNY, true).reviewVerdict(modification, null);

        assertThat(verdict.issues()).noneMatch(i ->
                ReflectAgent.PlanIssue.TYPE_TRANSFER_TIME.equals(i.type())
                        || ReflectAgent.PlanIssue.TYPE_WEATHER_OUTDOOR.equals(i.type()));
    }

    @Test
    @DisplayName("预算超限仍是 HIGH（既有硬约束不回退）")
    void budgetOverrunRemainsHardViolation() {
        AgentPlan plan = plan(100, List.of(
                attraction(1, "外滩", "10:00", "11:30", 31.23, 121.47, 500)));

        ReflectAgent.ReviewVerdict verdict = agent(SUNNY, false).reviewVerdict(plan, null);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.issues()).anySatisfy(issue -> {
            assertThat(issue.type()).isEqualTo(ReflectAgent.PlanIssue.TYPE_BUDGET);
            assertThat(issue.severity()).isEqualTo(ReflectAgent.PlanIssue.SEVERITY_HIGH);
        });
    }
}