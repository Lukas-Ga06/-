package com.travel.agent;

import com.travel.agent.entity.Trip;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行程生命周期：状态（待确认/已确认/已取消）+ 按日期派生的阶段（未开始/进行中/已结束）。
 * 背景：之前 status 只在创建时赋值，手动建的行程永远是"规划中"，也无法表达"已结束/已取消"。
 */
class TripLifecycleTest extends IntegrationTestSupport {

    @Autowired
    private TripRepository tripRepository;

    private long createTrip(Account account, LocalDate start, LocalDate end) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "生命周期测试",
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

    @Test
    @DisplayName("阶段由日期派生：未开始 / 进行中 / 已结束")
    void phaseIsDerivedFromDates() throws Exception {
        Account account = registerNew();
        LocalDate today = LocalDate.now();

        long upcomingId = createTrip(account, today.plusDays(10), today.plusDays(12));
        MvcResult upcoming = mockMvc.perform(get("/api/trips/" + upcomingId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(upcoming).path("data").path("phase").asText()).isEqualTo("UPCOMING");
        assertThat(body(upcoming).path("data").path("daysUntilStart").asLong()).isEqualTo(10);

        long ongoingId = createTrip(account, today.minusDays(1), today.plusDays(1));
        MvcResult ongoing = mockMvc.perform(get("/api/trips/" + ongoingId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(ongoing).path("data").path("phase").asText()).isEqualTo("ONGOING");

        long pastId = createTrip(account, today.minusDays(10), today.minusDays(5));
        MvcResult past = mockMvc.perform(get("/api/trips/" + pastId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(past).path("data").path("phase").asText()).isEqualTo("PAST");
    }

    @Test
    @DisplayName("确认 / 取消 / 恢复，非法状态被拒绝")
    void statusTransitions() throws Exception {
        Account account = registerNew();
        LocalDate today = LocalDate.now();
        long tripId = createTrip(account, today.plusDays(3), today.plusDays(5));

        String confirmed = mockMvc.perform(put("/api/trips/" + tripId + "/status")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(confirmed).path("data").path("status").asText()).isEqualTo("CONFIRMED");

        String cancelled = mockMvc.perform(put("/api/trips/" + tripId + "/status")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(cancelled).path("data").path("phase").asText()).isEqualTo("CANCELLED");

        // 允许取消后恢复
        mockMvc.perform(put("/api/trips/" + tripId + "/status")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/trips/" + tripId + "/status")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_A_STATUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("已取消的行程不计入足迹地图")
    void cancelledTripIsExcludedFromFootprint() throws Exception {
        Account account = registerNew();
        LocalDate today = LocalDate.now();
        long tripId = createTrip(account, today.plusDays(3), today.plusDays(5));

        mockMvc.perform(get("/api/trips/map").header("Authorization", account.bearer()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/trips/" + tripId + "/status")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        MvcResult map = mockMvc.perform(get("/api/trips/map").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(map).path("data")).isEmpty();

        Trip persisted = tripRepository.findById(tripId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(Trip.STATUS_CANCELLED);
        assertThat(persisted.phase(today)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("阶段 D：TripResponse 暴露乐观锁 version，且改一次行程版本就递增")
    void versionIsExposedAndBumpedOnUpdate() throws Exception {
        Account account = registerNew();
        LocalDate today = LocalDate.now();
        long tripId = createTrip(account, today.plusDays(3), today.plusDays(5));

        MvcResult first = mockMvc.perform(get("/api/trips/" + tripId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        // 前端靠这个字段判断「是不是在别的标签页/设备上改过」，缺失就不能做主动冲突提示
        assertThat(body(first).path("data").has("version")).isTrue();
        long versionBefore = body(first).path("data").path("version").asLong();

        String update = objectMapper.writeValueAsString(Map.of(
                "name", "生命周期测试·改名",
                "destination", "上海",
                "startDate", today.plusDays(3).toString(),
                "endDate", today.plusDays(5).toString(),
                "budget", 3200));
        mockMvc.perform(put("/api/trips/" + tripId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk());

        MvcResult second = mockMvc.perform(get("/api/trips/" + tripId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(second).path("data").path("version").asLong()).isEqualTo(versionBefore + 1);
    }
}
