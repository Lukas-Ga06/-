package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.ExecutorAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PlannerAgent;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规划是否使用用户说的出发日期；以及重新规划已有行程不会创建重复行程。
 */
class TripPlanningDateTest extends IntegrationTestSupport {

    @Autowired
    private PlannerAgent plannerAgent;

    @Autowired
    private ExecutorAgent executorAgent;

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private TripRepository tripRepository;

    private AgentContext context(String message, Long tripId) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("u_test");
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage(message);
        ctx.setTripId(tripId);
        return ctx;
    }

    private AgentPlan plan(String message, Trip trip) {
        IntentResult intent = IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .constraints(new HashMap<>())
                .build();
        return plannerAgent.plan(context(message, trip == null ? null : trip.getId()), intent, trip);
    }

    private static LocalDate nextOctoberFirst(LocalDate today) {
        LocalDate d = LocalDate.of(today.getYear(), 10, 1);
        return d.isBefore(today) ? d.plusYears(1) : d;
    }

    @Test
    @DisplayName("「10月1日去杭州」按用户给的日期规划，而不是永远从明天开始")
    void usesStatedDepartureDate() {
        AgentPlan plan = plan("帮我规划10月1日杭州三日游，预算3000元", null);

        assertThat(plan.getTripDraft()).isNotNull();
        assertThat(plan.getTripDraft().getStartDate()).isEqualTo(nextOctoberFirst(LocalDate.now()));
        // 「11月1日…三日游」里的日期不能被当成天数
        assertThat(plan.getTripDraft().getDays()).isEqualTo(3);
        assertThat(plan.getOperations()).isNotEmpty();
        assertThat(plan.getReasoning()).contains("出发");
    }

    @Test
    @DisplayName("完整日期已过去时明确拒绝，而不是悄悄改到明天")
    void rejectsPastExplicitDate() {
        AgentPlan plan = plan("帮我规划2025-10-01杭州三日游", null);

        assertThat(plan.getOperations()).isEmpty();
        assertThat(plan.getReasoning()).contains("已经过去");
    }

    @Test
    @DisplayName("在已有行程上重新规划：更新原行程（不重复创建），日期保持不变")
    void replanUpdatesExistingTripWithoutDuplicating() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(10);
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("杭州行程");
        request.setDestination("杭州");
        request.setStartDate(start.toString());
        request.setEndDate(start.plusDays(2).toString());
        request.setBudget(BigDecimal.valueOf(3000));
        Trip trip = tripService.create(account.id(), request);
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(1, ItineraryItem.TYPE_ATTRACTION,
                "旧安排", "09:00", "10:00", BigDecimal.ZERO, null, null, null, null));

        AgentPlan plan = plan("帮我重新规划杭州三日游", trip);
        assertThat(plan.getTripDraft().getStartDate()).isEqualTo(start);
        // 重新规划沿用原行程的预算与天数（消息里没提新预算/天数）
        assertThat(plan.getTripDraft().getBudget()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        assertThat(plan.getTripDraft().getDays()).isEqualTo(3);

        executorAgent.commit(plan, account.id());

        assertThat(tripRepository.findByUserIdOrderByCreatedAtDesc(account.id())).hasSize(1);
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId());
        assertThat(items).isNotEmpty();
        assertThat(items).noneMatch(i -> "旧安排".equals(i.getTitle()));
        // 新条目仍锚定在原行程的出发日期上
        assertThat(items).allMatch(i -> i.getStartTime() == null
                || i.getStartTime().toLocalDate().equals(start.plusDays(Math.max(i.getDayIndex(), 1) - 1L)));
    }
}
