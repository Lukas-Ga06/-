package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.dto.AgentDtos;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认时的版本校验：Diff 生成后行程若被改动，旧方案必须失效，不能覆盖新数据。
 */
class PendingPlanRevisionTest extends IntegrationTestSupport {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("版本校验测试");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private void addItem(Trip trip, int day, String title) {
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                day, ItineraryItem.TYPE_ATTRACTION, title, "09:00", "10:00",
                BigDecimal.ZERO, null, null, null, null));
    }

    private void addOutdoorItem(Trip trip, int day, String title) {
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                day, ItineraryItem.TYPE_ATTRACTION, title, "09:00", "10:00",
                BigDecimal.ZERO, null, null, java.util.List.of("室外"), null));
    }

    private AgentDtos.AgentResult ask(Account account, Long tripId, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setTripId(tripId);
        ctx.setMessage(message);
        return agentEngine.handle(ctx);
    }

    @Test
    @DisplayName("方案生成后行程被修改 → 确认被拒绝，且不落库")
    void stalePlanIsRejected() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, 1, "原有安排");

        AgentDtos.AgentResult plan = ask(account, trip.getId(), "帮我重新规划上海三日游，2人，预算3000元");
        assertThat(plan.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        String planId = plan.getConfirmation().getPlanId();

        // 另一个标签页/设备上改了行程
        addItem(trip, 2, "确认前新增的安排");

        AgentDtos.AgentResult result = agentEngine.confirm(planId, account.id());

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(result.getMessage()).contains("已经发生变化");
        // 旧方案没有落库：原安排 + 新加的安排都还在
        assertThat(itineraryService.listEntities(trip.getId())).hasSize(2);
    }

    @Test
    @DisplayName("行程没变化时确认正常提交")
    void freshPlanConfirms() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, 1, "原有安排");

        AgentDtos.AgentResult plan = ask(account, trip.getId(), "帮我重新规划上海三日游，2人，预算3000元");
        AgentDtos.AgentResult result = agentEngine.confirm(plan.getConfirmation().getPlanId(), account.id());

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(itineraryService.listEntities(trip.getId())).isNotEmpty();
    }

    @Test
    @DisplayName("新建行程的方案没有指纹，正常提交")
    void newTripPlanHasNoRevision() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult plan = ask(account, null, "帮我规划上海三日游，2人，预算3000元");
        AgentDtos.AgentResult result = agentEngine.confirm(plan.getConfirmation().getPlanId(), account.id());

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getTripId()).isNotNull();
    }

    @Test
    @DisplayName("未传 currentDay 时修改行程不会 NPE（曾经的三元拆箱 bug）")
    void modificationWithoutCurrentDayDoesNotCrash() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addOutdoorItem(trip, 1, "外滩");

        AgentDtos.AgentResult result = ask(account, trip.getId(), "把户外景点换掉");

        assertThat(result.getIntent()).isNotEqualTo("ERROR");
        assertThat(result.getMessage()).doesNotContain("抱歉");
        assertThat(result.getStatus()).isIn(AgentDtos.STATUS_NEED_CONFIRMATION, AgentDtos.STATUS_COMPLETED);
    }
}
