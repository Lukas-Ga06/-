package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PlanOperation;
import com.travel.agent.agent.ReflectAgent;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间冲突检查必须基于「应用计划之后」的行程：
 * 既覆盖 ADD 造成的新重叠，也覆盖 MODIFY 把某个安排移动到重叠位置的情况。
 */
class ReflectAgentTest extends IntegrationTestSupport {

    @Autowired
    private ReflectAgent reflectAgent;

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("冲突检查测试");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private ItineraryItem addItem(Trip trip, String title, String start, String end) {
        return itineraryService.addItem(trip,
                new ItineraryDtos.AddItemRequest(1, ItineraryItem.TYPE_ATTRACTION, title,
                        start, end, BigDecimal.ZERO, null, null, null, null));
    }

    private static AgentPlan planWith(List<PlanOperation> operations) {
        return AgentPlan.builder()
                .planId("plan-reflect")
                .intent(IntentResult.MODIFY_ITINERARY)
                .operations(operations)
                .build();
    }

    private static ItineraryItem draft(Trip trip, String title, int startHour, int endHour) {
        return ItineraryItem.builder()
                .dayIndex(1)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title(title)
                .startTime(trip.getStartDate().atTime(startHour, 0))
                .endTime(trip.getStartDate().atTime(endHour, 0))
                .build();
    }

    @Test
    @DisplayName("新增条目与已有安排重叠时会给出警告")
    void addedItemOverlappingExistingIsWarned() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, "外滩", "10:00", "11:30");

        AgentPlan plan = planWith(List.of(PlanOperation.builder()
                .type(PlanOperation.ADD)
                .draft(draft(trip, "重叠的新景点", 11, 12))
                .build()));

        assertThat(reflectAgent.review(plan, trip))
                .anyMatch(w -> w.contains("时间重叠") && w.contains("外滩") && w.contains("重叠的新景点"));
    }

    @Test
    @DisplayName("修改条目时间造成的重叠也会被警告（旧实现只看 ADD）")
    void modifiedItemOverlappingExistingIsWarned() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, "外滩", "10:00", "11:30");
        ItineraryItem second = addItem(trip, "豫园", "13:30", "15:00");

        AgentPlan plan = planWith(List.of(PlanOperation.builder()
                .type(PlanOperation.MODIFY)
                .targetItemId(second.getId())
                .draft(draft(trip, "豫园（改）", 11, 12))
                .build()));

        assertThat(reflectAgent.review(plan, trip))
                .anyMatch(w -> w.contains("时间重叠") && w.contains("外滩"));
    }

    @Test
    @DisplayName("不重叠的计划没有任何时间警告")
    void nonOverlappingPlanHasNoTimeWarning() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, "外滩", "10:00", "11:30");

        AgentPlan plan = planWith(List.of(PlanOperation.builder()
                .type(PlanOperation.ADD)
                .draft(draft(trip, "下午的博物馆", 14, 16))
                .build()));

        assertThat(reflectAgent.review(plan, trip)).noneMatch(w -> w.contains("时间重叠"));
    }
}
