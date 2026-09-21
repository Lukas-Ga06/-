package com.travel.agent;

import com.travel.agent.entity.OperationHistory;
import com.travel.agent.repository.ExpenseRepository;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 删除行程必须级联清理关联数据。
 * 回归背景：旧实现只删行程与条目，账单会变成孤儿——既继续计入总支出，又因为需要 trip 归属校验而无法删除。
 */
class TripDeletionTest extends IntegrationTestSupport {

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private ItineraryItemRepository itineraryItemRepository;

    @Autowired
    private OperationHistoryRepository operationHistoryRepository;

    @Autowired
    private ExpenseService expenseService;

    private long createTrip(Account owner) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/trips")
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"级联删除测试\",\"destination\":\"上海\",\"budget\":3000}"))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("删除行程会一并清理账单、行程项与操作历史")
    void deletingTripCascadesToRelatedData() throws Exception {
        Account account = registerNew();
        long tripId = createTrip(account);

        mockMvc.perform(post("/api/trips/" + tripId + "/expenses")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"餐饮\",\"amount\":45}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/trips/" + tripId + "/itinerary")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayIndex\":1,\"itemType\":\"attraction\",\"title\":\"外滩\"}"))
                .andExpect(status().isOk());

        assertThat(expenseService.overview(account.id()).getTotalSpent()).isEqualByComparingTo(new BigDecimal("45"));
        assertThat(operationHistoryRepository
                .findFirstByTripIdAndStatusOrderByIdDesc(tripId, OperationHistory.STATUS_ACTIVE)).isPresent();

        mockMvc.perform(delete("/api/trips/" + tripId).header("Authorization", account.bearer()))
                .andExpect(status().isOk());

        assertThat(expenseRepository.findByTripIdOrderBySpentAtDescIdDesc(tripId)).isEmpty();
        assertThat(itineraryItemRepository.findByTripIdOrderByDayIndexAscStartTimeAsc(tripId)).isEmpty();
        assertThat(operationHistoryRepository
                .findFirstByTripIdAndStatusOrderByIdDesc(tripId, OperationHistory.STATUS_ACTIVE)).isEmpty();
        // 孤儿账单不再计入总支出
        assertThat(expenseService.overview(account.id()).getTotalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
