package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预算三套口径对账：计划成本（行程项）/ 行程预算 / 实际支出（记账），以及分类对账。
 */
class BudgetExecutionTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private ExpenseService expenseService;

    private Trip newTrip(long userId, BigDecimal budget) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("预算对账测试");
        request.setDestination("上海");
        request.setBudget(budget);
        return tripService.create(userId, request);
    }

    private void addItem(Trip trip, String type, String title, double cost) {
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                1, type, title, "09:00", "10:00",
                BigDecimal.valueOf(cost), null, null, null, null));
    }

    private JsonNode execution(Account account, long tripId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/trips/" + tripId + "/budget")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private static Map<String, JsonNode> categories(JsonNode execution) {
        Map<String, JsonNode> map = new HashMap<>();
        execution.path("categories").forEach(c -> map.put(c.path("category").asText(), c));
        return map;
    }

    @Test
    @DisplayName("计划 / 预算 / 实际 与偏差、分类对账")
    void reconcilesPlannedBudgetAndActual() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id(), BigDecimal.valueOf(3000));
        addItem(trip, ItineraryItem.TYPE_HOTEL, "酒店", 500);
        addItem(trip, ItineraryItem.TYPE_RESTAURANT, "午餐", 200);
        addItem(trip, ItineraryItem.TYPE_TRANSPORT, "打车", 50);
        expenseService.add(trip.getId(), account.id(), "餐饮", BigDecimal.valueOf(260), "午餐", LocalDate.now(), "现金");
        expenseService.add(trip.getId(), account.id(), "交通", BigDecimal.valueOf(40), "打车", LocalDate.now(), "现金");

        JsonNode e = execution(account, trip.getId());

        assertThat(e.path("budget").decimalValue()).isEqualByComparingTo("3000");
        assertThat(e.path("planned").decimalValue()).isEqualByComparingTo("750");
        assertThat(e.path("actual").decimalValue()).isEqualByComparingTo("300");
        assertThat(e.path("remaining").decimalValue()).isEqualByComparingTo("2700");
        assertThat(e.path("variance").decimalValue()).isEqualByComparingTo("-450");
        assertThat(e.path("overBudget").asBoolean()).isFalse();

        Map<String, JsonNode> cats = categories(e);
        assertThat(cats.get("住宿").path("planned").decimalValue()).isEqualByComparingTo("500");
        assertThat(cats.get("住宿").path("actual").decimalValue()).isEqualByComparingTo("0");
        assertThat(cats.get("餐饮").path("planned").decimalValue()).isEqualByComparingTo("200");
        assertThat(cats.get("餐饮").path("actual").decimalValue()).isEqualByComparingTo("260");
        assertThat(cats.get("交通").path("planned").decimalValue()).isEqualByComparingTo("50");
        assertThat(cats.get("交通").path("actual").decimalValue()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("实际支出超出预算时标记 overBudget，余额为负")
    void flagsOverBudget() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id(), BigDecimal.valueOf(100));
        addItem(trip, ItineraryItem.TYPE_RESTAURANT, "午餐", 80);
        expenseService.add(trip.getId(), account.id(), "餐饮", BigDecimal.valueOf(150), "晚餐", LocalDate.now(), "现金");

        JsonNode e = execution(account, trip.getId());

        assertThat(e.path("overBudget").asBoolean()).isTrue();
        assertThat(e.path("remaining").decimalValue()).isEqualByComparingTo("-50");
        assertThat(e.path("variance").decimalValue()).isEqualByComparingTo("70");
        assertThat(e.path("planOverBudget").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("不能查看他人行程的预算执行")
    void otherUserCannotReadBudget() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        Trip trip = newTrip(owner.id(), BigDecimal.valueOf(3000));

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/budget")
                        .header("Authorization", intruder.bearer()))
                .andExpect(status().isNotFound());
    }
}
