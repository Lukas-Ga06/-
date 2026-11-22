package com.travel.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 改期一致性：行程改起始日期时，条目绝对时间必须整体平移；
 * 缩短行程时不允许把安排静默挤出范围；撤销快照要按新日期重新锚定。
 */
class TripRescheduleTest extends IntegrationTestSupport {

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private TripService tripService;

    @Autowired
    private OperationHistoryService operationHistoryService;

    private long createTrip(Account account, LocalDate start, LocalDate end) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "改期测试",
                "destination", "上海",
                "startDate", start.toString(),
                "endDate", end.toString(),
                "budget", 3000));
        MvcResult result = mockMvc.perform(post("/api/trips")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private void addItem(Account account, long tripId, int day, String title) throws Exception {
        mockMvc.perform(post("/api/trips/" + tripId + "/itinerary")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayIndex\":" + day + ",\"itemType\":\"attraction\",\"title\":\"" + title
                                + "\",\"startTime\":\"10:00\",\"endTime\":\"11:30\"}"))
                .andExpect(status().isOk());
    }

    private void reschedule(Account account, long tripId, LocalDate start, LocalDate end) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "startDate", start.toString(),
                "endDate", end.toString()));
        mockMvc.perform(put("/api/trips/" + tripId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("改起始日期时，条目时间整体平移，dayIndex 不变")
    void rescheduleShiftsItemTimes() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        long tripId = createTrip(account, start, start.plusDays(2));
        addItem(account, tripId, 2, "外滩");

        LocalDate newStart = start.plusDays(7);
        reschedule(account, tripId, newStart, newStart.plusDays(2));

        List<ItineraryItem> items = itineraryService.listEntities(tripId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getDayIndex()).isEqualTo(2);
        assertThat(items.get(0).getStartTime()).isEqualTo(newStart.plusDays(1).atTime(LocalTime.of(10, 0)));
        assertThat(items.get(0).getEndTime()).isEqualTo(newStart.plusDays(1).atTime(LocalTime.of(11, 30)));
    }

    @Test
    @DisplayName("缩短行程导致安排越界时拒绝更新，并说明原因")
    void shrinkingTripWithOutOfRangeItemsIsRejected() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        long tripId = createTrip(account, start, start.plusDays(2));
        addItem(account, tripId, 3, "第三天的活动");

        String body = objectMapper.writeValueAsString(Map.of(
                "startDate", start.toString(),
                "endDate", start.plusDays(1).toString()));
        MvcResult result = mockMvc.perform(put("/api/trips/" + tripId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(body(result).path("message").asText()).contains("第 3 天");
        // 行程日期没有被改动
        assertThat(tripService.getOwned(tripId, account.id()).getEndDate()).isEqualTo(start.plusDays(2));
    }

    @Test
    @DisplayName("改期后撤销：条目按新行程日期重新锚定，不会回到旧日期")
    void undoAfterRescheduleReanchorsToNewDates() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        long tripId = createTrip(account, start, start.plusDays(2));
        addItem(account, tripId, 1, "第一次添加");
        addItem(account, tripId, 2, "第二次添加");

        LocalDate newStart = start.plusDays(14);
        reschedule(account, tripId, newStart, newStart.plusDays(2));

        Trip trip = tripService.getOwned(tripId, account.id());
        operationHistoryService.undoLatest(trip, account.id());

        List<ItineraryItem> items = itineraryService.listEntities(tripId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTitle()).isEqualTo("第一次添加");
        assertThat(items.get(0).getDayIndex()).isEqualTo(1);
        assertThat(items.get(0).getStartTime()).isEqualTo(newStart.atTime(LocalTime.of(10, 0)));
    }
}
