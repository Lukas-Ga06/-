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
    @DisplayName("按天偏差：天内计划 vs 实际；行程外支出归 day=0；与总量偏差一致")
    void breaksDownVarianceByDay() throws Exception {
        Account account = registerNew();
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("按天偏差测试");
        request.setDestination("上海");
        request.setStartDate("2026-11-01");
        request.setEndDate("2026-11-02");
        request.setBudget(BigDecimal.valueOf(2000));
        Trip trip = tripService.create(account.id(), request);

        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                1, ItineraryItem.TYPE_RESTAURANT, "Day1 午餐", "12:00", "13:00",
                BigDecimal.valueOf(100), null, null, null, null));
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                2, ItineraryItem.TYPE_ATTRACTION, "Day2 景点", "14:00", "16:00",
                BigDecimal.valueOf(200), null, null, null, null));
        // Day1 实际 120（超 20）、Day2 实际 150（省 50）、行程外（行前采购）80
        expenseService.add(trip.getId(), account.id(), "餐饮", BigDecimal.valueOf(120), "午餐",
                LocalDate.of(2026, 11, 1), "现金");
        expenseService.add(trip.getId(), account.id(), "门票", BigDecimal.valueOf(150), "门票",
                LocalDate.of(2026, 11, 2), "现金");
        expenseService.add(trip.getId(), account.id(), "购物", BigDecimal.valueOf(80), "行前采购",
                LocalDate.of(2026, 10, 28), "现金");

        JsonNode e = execution(account, trip.getId());

        Map<Integer, JsonNode> daily = new HashMap<>();
        e.path("daily").forEach(d -> daily.put(d.path("day").asInt(), d));
        assertThat(daily).containsOnlyKeys(0, 1, 2);
        assertThat(daily.get(1).path("planned").decimalValue()).isEqualByComparingTo("100");
        assertThat(daily.get(1).path("actual").decimalValue()).isEqualByComparingTo("120");
        assertThat(daily.get(1).path("variance").decimalValue()).isEqualByComparingTo("20");
        assertThat(daily.get(2).path("planned").decimalValue()).isEqualByComparingTo("200");
        assertThat(daily.get(2).path("variance").decimalValue()).isEqualByComparingTo("-50");
        assertThat(daily.get(0).path("planned").decimalValue()).isEqualByComparingTo("0");
        assertThat(daily.get(0).path("actual").decimalValue()).isEqualByComparingTo("80");
        // 每日偏差之和 == 总量偏差（350 - 300 = 50）
        assertThat(e.path("variance").decimalValue()).isEqualByComparingTo("50");
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
