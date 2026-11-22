package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.ExecutorAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PlanOperation;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提交 / 撤销一致性（P0-4）。
 *
 * 回归背景：ItineraryService.replaceAll 旧实现是「deleteByTripId + saveAll」整表重建——
 * 撤销一次会把所有条目物理删除再按快照重插，条目 id 全部变化；任何基于 id 的快照/前端选中
 * 都在撤销后失效，而且撤销期间新写入的条目会被一并抹掉。
 * 现在改为按 id diff（已有→就地更新、缺失→删除、新增→插入），id 保持稳定。
 *
 * 说明：这里用顺序用例覆盖确定性语义（并发版本受线程调度影响，无法稳定断言）；
 * 并发确认链路的原子性由 ConcurrentConfirmProbeTest#concurrentConfirmCreatesExactlyOneTrip 覆盖。
 */
class ConcurrentCommitHistoryTest extends IntegrationTestSupport {

    @Autowired
    private ExecutorAgent executorAgent;

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private OperationHistoryService operationHistoryService;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("提交撤销一致性测试");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    /** 每次提交各 ADD 一条不同标题的条目（模拟「两次 AI 调整」） */
    private static AgentPlan planWithOneNewItem(Trip trip, String title, int hour) {
        ItineraryItem draft = ItineraryItem.builder()
                .dayIndex(1)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title(title)
                .startTime(LocalDateTime.of(2026, 1, 1, hour, 0))
                .endTime(LocalDateTime.of(2026, 1, 1, hour + 1, 0))
                .cost(BigDecimal.valueOf(80))
                .build();
        return AgentPlan.builder()
                .planId("plan-" + UUID.randomUUID())
                .intent(IntentResult.MODIFY_ITINERARY)
                .tripId(trip.getId())
                .reasoning("测试：新增 " + title)
                .operations(List.of(PlanOperation.builder().type(PlanOperation.ADD).draft(draft).build()))
                .build();
    }

    private List<String> titles(Long tripId) {
        return itineraryService.listEntities(tripId).stream().map(ItineraryItem::getTitle).toList();
    }

    @Test
    @DisplayName("同一行程连续两次提交，撤销一步只回退一步：上一条仍在且 id 不变")
    void undoOneStepKeepsPreviousCommitAndItsId() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());

        AgentDtos.AgentResult first = executorAgent.commit(planWithOneNewItem(trip, "第一次提交的景点", 9), account.id());
        assertThat(first.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        Long firstItemId = itineraryService.listEntities(trip.getId()).get(0).getId();
        assertThat(firstItemId).isNotNull();

        AgentDtos.AgentResult second = executorAgent.commit(planWithOneNewItem(trip, "第二次提交的景点", 14), account.id());
        assertThat(second.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(titles(trip.getId()))
                .containsExactlyInAnyOrder("第一次提交的景点", "第二次提交的景点");

        // 撤销最近一次提交（第二次）→ 快照是「只有第一次提交」的那一版
        String message = operationHistoryService.undoLatest(tripService.getOwned(trip.getId(), account.id()), account.id());
        assertThat(message).contains("已撤销");

        List<ItineraryItem> afterUndo = itineraryService.listEntities(trip.getId());
        assertThat(afterUndo).extracting(ItineraryItem::getTitle)
                .as("撤销一步只应回退第二次提交，第一次提交的条目必须仍在")
                .containsExactly("第一次提交的景点");
        assertThat(afterUndo.get(0).getId())
                .as("撤销必须按 id 就地还原，不能整表重建（id 变化会让历史快照/前端选中失效）")
                .isEqualTo(firstItemId);
    }

    @Test
    @DisplayName("连续撤销两次逐级回退到空，不会一次抹掉两条里的一条以外的东西")
    void twoUndosWalkBackOneStepAtATime() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        executorAgent.commit(planWithOneNewItem(trip, "第一次提交的景点", 9), account.id());
        executorAgent.commit(planWithOneNewItem(trip, "第二次提交的景点", 14), account.id());

        operationHistoryService.undoLatest(tripService.getOwned(trip.getId(), account.id()), account.id());
        assertThat(titles(trip.getId())).containsExactly("第一次提交的景点");

        operationHistoryService.undoLatest(tripService.getOwned(trip.getId(), account.id()), account.id());
        assertThat(titles(trip.getId()))
                .as("第二次撤销回退到两次提交之前的空快照")
                .isEmpty();
    }

    @Test
    @DisplayName("撤销只影响本行程：另一行程的条目不受影响")
    void undoDoesNotTouchOtherTrips() throws Exception {
        Account account = registerNew();
        Trip target = newTrip(account.id());
        Trip other = newTrip(account.id());
        executorAgent.commit(planWithOneNewItem(other, "别的行程的景点", 9), account.id());
        executorAgent.commit(planWithOneNewItem(target, "目标行程的景点", 14), account.id());

        operationHistoryService.undoLatest(tripService.getOwned(target.getId(), account.id()), account.id());

        assertThat(titles(target.getId())).isEmpty();
        assertThat(titles(other.getId())).containsExactly("别的行程的景点");
    }
}