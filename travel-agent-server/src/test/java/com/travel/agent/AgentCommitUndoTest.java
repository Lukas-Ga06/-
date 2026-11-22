package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.ExecutorAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PlanOperation;
import com.travel.agent.agent.TripDraft;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HITL 核心链路：确认提交 → 写操作历史 → 撤销还原 */
class AgentCommitUndoTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private ExecutorAgent executorAgent;

    @Autowired
    private OperationHistoryService operationHistoryService;

    @Autowired
    private TripRepository tripRepository;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("确认撤销测试行程");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private static AgentPlan planWithOneNewItem(Trip trip) {
        ItineraryItem draft = ItineraryItem.builder()
                .dayIndex(1)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title("豫园")
                .startTime(LocalDateTime.of(2026, 1, 1, 14, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, 16, 0))
                .cost(BigDecimal.valueOf(80))
                .build();
        return AgentPlan.builder()
                .planId("plan-" + UUID.randomUUID())
                .intent(IntentResult.MODIFY_ITINERARY)
                .tripId(trip.getId())
                .reasoning("测试：新增一个景点")
                .operations(List.of(PlanOperation.builder().type(PlanOperation.ADD).draft(draft).build()))
                .build();
    }

    @Test
    @DisplayName("确认提交后行程落库，撤销后还原到提交前")
    void commitThenUndoRestoresItinerary() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        assertThat(itineraryService.listEntities(trip.getId())).isEmpty();

        AgentDtos.AgentResult committed = executorAgent.commit(planWithOneNewItem(trip), account.id());
        assertThat(committed.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(itineraryService.listEntities(trip.getId())).hasSize(1);

        String undoMessage = operationHistoryService.undoLatest(trip, account.id());
        assertThat(undoMessage).contains("已撤销");
        assertThat(itineraryService.listEntities(trip.getId())).isEmpty();
    }

    @Test
    @DisplayName("撤销需要存在可撤销的操作")
    void undoWithoutHistoryFails() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> operationHistoryService.undoLatest(trip, account.id()))
                .isInstanceOf(com.travel.agent.exception.BusinessException.class);
    }

    @Test
    @DisplayName("ExecutorAgent.commit 必须是事务方法：行程变更与撤销历史必须同生共死")
    void commitIsTransactional() throws Exception {
        Method commit = ExecutorAgent.class.getMethod("commit", AgentPlan.class, Long.class);
        assertThat(commit.isAnnotationPresent(Transactional.class))
                .as("commit 未标注 @Transactional 时，行程已改但撤销历史写入失败会导致用户无法撤销")
                .isTrue();
    }

    @Test
    @DisplayName("手工新增行程项会进入撤销链，撤销后回到手工修改前")
    void manualItineraryEditIsUndoable() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/itinerary")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayIndex\":1,\"itemType\":\"attraction\",\"title\":\"手工加的景点\"}"))
                .andExpect(status().isOk());
        assertThat(itineraryService.listEntities(trip.getId())).hasSize(1);

        String message = operationHistoryService.undoLatest(trip, account.id());

        assertThat(message).contains("已撤销");
        assertThat(itineraryService.listEntities(trip.getId())).isEmpty();
    }

    @Test
    @DisplayName("撤销「新建行程」会删除整个行程，而不是留下一条空壳行程")
    void undoTripCreationDeletesTrip() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        TripDraft draft = TripDraft.builder()
                .name("新行程").destination("上海")
                .startDate(start).days(2).budget(BigDecimal.valueOf(3000))
                .preferences(List.of())
                .build();
        ItineraryItem draftItem = ItineraryItem.builder()
                .dayIndex(1)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title("外滩")
                .startTime(start.atTime(9, 0))
                .endTime(start.atTime(11, 0))
                .cost(BigDecimal.valueOf(100))
                .build();
        AgentPlan plan = AgentPlan.builder()
                .planId("plan-create-" + UUID.randomUUID())
                .intent(IntentResult.PLAN_TRIP)
                .tripDraft(draft)
                .operations(List.of(PlanOperation.builder().type(PlanOperation.ADD).draft(draftItem).build()))
                .reasoning("测试：新建行程")
                .build();

        AgentDtos.AgentResult committed = executorAgent.commit(plan, account.id());
        Long tripId = committed.getTripId();
        assertThat(tripId).isNotNull();
        assertThat(tripRepository.findById(tripId)).isPresent();

        String message = operationHistoryService.undoLatest(tripRepository.findById(tripId).orElseThrow(), account.id());

        assertThat(message).contains("已撤销新建行程");
        assertThat(tripRepository.findById(tripId)).isEmpty();
    }
}
