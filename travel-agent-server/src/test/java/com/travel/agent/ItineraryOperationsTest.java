package com.travel.agent;

import com.travel.agent.agent.PlanOperation;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Agent 计划落地：ADD / MODIFY / REMOVE 与非法操作类型 */
class ItineraryOperationsTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("操作测试行程");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private static PlanOperation addOperation(String title, BigDecimal cost) {
        return PlanOperation.builder()
                .type(PlanOperation.ADD)
                .draft(ItineraryItem.builder()
                        .dayIndex(1)
                        .itemType(ItineraryItem.TYPE_ATTRACTION)
                        .title(title)
                        .startTime(LocalDateTime.of(2026, 1, 1, 9, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 11, 0))
                        .cost(cost)
                        .build())
                .build();
    }

    @Test
    @DisplayName("ADD / MODIFY / REMOVE 依次生效")
    void addModifyRemoveAreApplied() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());

        itineraryService.applyOperations(trip.getId(), List.of(addOperation("外滩", BigDecimal.valueOf(100))));
        List<ItineraryItem> afterAdd = itineraryService.listEntities(trip.getId());
        assertThat(afterAdd).hasSize(1);
        assertThat(afterAdd.get(0).getTripId()).isEqualTo(trip.getId());
        assertThat(afterAdd.get(0).getTitle()).isEqualTo("外滩");

        Long itemId = afterAdd.get(0).getId();
        itineraryService.applyOperations(trip.getId(), List.of(PlanOperation.builder()
                .type(PlanOperation.MODIFY)
                .targetItemId(itemId)
                .draft(ItineraryItem.builder()
                        .dayIndex(1)
                        .itemType(ItineraryItem.TYPE_ATTRACTION)
                        .title("外滩（改）")
                        .cost(BigDecimal.valueOf(120))
                        .build())
                .build()));
        assertThat(itineraryService.listEntities(trip.getId()).get(0).getTitle()).isEqualTo("外滩（改）");

        itineraryService.applyOperations(trip.getId(), List.of(PlanOperation.builder()
                .type(PlanOperation.REMOVE)
                .targetItemId(itemId)
                .build()));
        assertThat(itineraryService.listEntities(trip.getId())).isEmpty();
    }

    @Test
    @DisplayName("未知操作类型会被拒绝，不产生任何落库")
    void unknownOperationTypeIsRejected() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());

        assertThatThrownBy(() -> itineraryService.applyOperations(trip.getId(),
                List.of(PlanOperation.builder().type("NOT_A_REAL_OPERATION").build())))
                .isInstanceOf(BusinessException.class);

        assertThat(itineraryService.listEntities(trip.getId())).isEmpty();
    }

    @Test
    @DisplayName("计划不能修改或删除其它行程的条目")
    void operationsCannotTouchAnotherTrip() throws Exception {
        Account account = registerNew();
        Trip tripA = newTrip(account.id());
        Trip tripB = newTrip(account.id());

        itineraryService.applyOperations(tripA.getId(), List.of(addOperation("外滩", BigDecimal.valueOf(100))));
        Long itemIdOfA = itineraryService.listEntities(tripA.getId()).get(0).getId();

        assertThatThrownBy(() -> itineraryService.applyOperations(tripB.getId(), List.of(PlanOperation.builder()
                .type(PlanOperation.MODIFY)
                .targetItemId(itemIdOfA)
                .draft(ItineraryItem.builder().dayIndex(1).itemType(ItineraryItem.TYPE_ATTRACTION).title("被篡改").build())
                .build())))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> itineraryService.applyOperations(tripB.getId(), List.of(PlanOperation.builder()
                .type(PlanOperation.REMOVE)
                .targetItemId(itemIdOfA)
                .build())))
                .isInstanceOf(BusinessException.class);

        assertThat(itineraryService.listEntities(tripA.getId())).hasSize(1);
        assertThat(itineraryService.listEntities(tripA.getId()).get(0).getTitle()).isEqualTo("外滩");
    }
}
