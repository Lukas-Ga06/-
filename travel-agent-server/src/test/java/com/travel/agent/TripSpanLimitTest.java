package com.travel.agent;

import com.travel.agent.agent.TripDraft;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行程跨度上限（{@link TripService#MAX_TRIP_YEARS} 年：结束日期不晚于 startDate + 1 年）。
 *
 * <p>三条写入路径都必须受同一口径约束：手工新建、改期（合并日期后重算）、Agent 确认
 * （{@code createFromAgent}，不经过 DTO）。条目的「落点日期」由
 * {@code startDate + dayIndex - 1} 派生，所以另测 dayIndex 越界写入被拒。
 */
class TripSpanLimitTest extends IntegrationTestSupport {

    private static final int MAX_YEARS = TripService.MAX_TRIP_YEARS;

    @Autowired
    private TripService tripService;

    @Autowired
    private TripRepository tripRepository;

    private MvcResult createRequest(Account account, LocalDate start, LocalDate end, int expectedStatus)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "跨度上限测试",
                "destination", "北京",
                "startDate", start.toString(),
                "endDate", end.toString(),
                "budget", 5000));
        return mockMvc.perform(post("/api/trips")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    /** 建一个 start..end（含首尾）的行程，返回 id */
    private long createTrip(Account account, LocalDate start, LocalDate end) throws Exception {
        MvcResult result = createRequest(account, start, end, 200);
        return body(result).path("data").path("id").asLong();
    }

    /** 含首尾天数：跨闰年时「一年」是 366/367 天，测试按起始日动态算，不写死 365 */
    private static long daysInclusive(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    private static LocalDate lastAllowedEnd(LocalDate start) {
        return start.plusYears(MAX_YEARS);
    }

    @Test
    @DisplayName("边界：一年整可建，再晚一天返回 400 并说明当前跨度")
    void createAtBoundary() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate lastAllowed = lastAllowedEnd(start);

        long tripId = createTrip(account, start, lastAllowed);
        MvcResult created = mockMvc.perform(get("/api/trips/" + tripId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(created).path("data").path("endDate").asText())
                .isEqualTo(lastAllowed.toString());

        LocalDate tooLate = lastAllowed.plusDays(1);
        MvcResult tooLong = createRequest(account, start, tooLate, 400);
        String message = body(tooLong).path("message").asText();
        assertThat(message)
                .contains("行程跨度不能超过 " + MAX_YEARS + " 年")
                .contains("当前 " + daysInclusive(start, tooLate) + " 天");
    }

    @Test
    @DisplayName("改期：只带结束日期把跨度拉过一年被拒，原起止日期不变")
    void updateCannotExtendBeyondLimit() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        long tripId = createTrip(account, start, start.plusDays(9));

        // 只带 endDate：跨度要在服务端与库里已有的 startDate 合并后才成立
        mockMvc.perform(put("/api/trips/" + tripId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "endDate", lastAllowedEnd(start).plusDays(1).toString()))))
                .andExpect(status().isBadRequest());

        MvcResult unchanged = mockMvc.perform(get("/api/trips/" + tripId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(unchanged).path("data").path("endDate").asText())
                .isEqualTo(start.plusDays(9).toString());

        // 恰好一年整仍可改期
        mockMvc.perform(put("/api/trips/" + tripId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "endDate", lastAllowedEnd(start).toString()))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("条目落点：dayIndex 越界（0 / 超过天数）被拒，边界内可增可改")
    void itineraryItemMustStayWithinTripSpan() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        long tripId = createTrip(account, start, start.plusDays(2));

        MvcResult lastDay = addItem(account, tripId, 3, 200);
        long itemId = body(lastDay).path("data").path("id").asLong();
        assertThat(body(lastDay).path("data").path("dayIndex").asInt()).isEqualTo(3);

        MvcResult dayFour = addItem(account, tripId, 4, 400);
        assertThat(body(dayFour).path("message").asText())
                .contains("第 4 天超出行程范围")
                .contains("当前共 3 天");

        addItem(account, tripId, 0, 400);

        // 修改落点同样受限；改回边界内仍然成功
        mockMvc.perform(put("/api/itinerary/" + itemId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dayIndex", 4))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/itinerary/" + itemId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dayIndex", 2))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Agent 确认路径同样受限：超一年草稿直接拒绝，一年整草稿落库")
    void agentDraftRespectsLimit() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        int allowedDays = (int) daysInclusive(start, lastAllowedEnd(start));
        TripDraft tooLong = TripDraft.builder()
                .name("多年环游")
                .destination("全国")
                .startDate(start)
                .days(allowedDays + 1)
                .budget(BigDecimal.valueOf(100000))
                .build();

        assertThatThrownBy(() -> tripService.createFromAgent(account.id(), tooLong, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("行程跨度不能超过 " + MAX_YEARS + " 年");

        // 一年整草稿可以落库（Agent 路径没有 DTO 校验兜底，靠同一个服务端守卫）
        TripDraft boundary = TripDraft.builder()
                .name("一年环游")
                .destination("全国")
                .startDate(start)
                .days(allowedDays)
                .budget(BigDecimal.valueOf(100000))
                .build();
        assertThat(tripService.createFromAgent(account.id(), boundary, List.of()).getEndDate())
                .isEqualTo(lastAllowedEnd(start));
    }

    @Test
    @DisplayName("历史超长行程（规则上线前创建）：只改名不受影响，动日期才校验")
    void legacyOverLimitTripCanBeRenamed() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        // 直接落一条早于本规则的历史数据：跨度 2 年
        Trip legacy = tripRepository.save(Trip.builder()
                .userId(account.id())
                .name("历史超长行程")
                .destination("全国")
                .startDate(start)
                .endDate(start.plusYears(2))
                .budget(BigDecimal.valueOf(1000))
                .status(Trip.STATUS_PLANNING)
                .build());

        mockMvc.perform(put("/api/trips/" + legacy.getId())
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "改个名"))))
                .andExpect(status().isOk());

        // 一旦动日期：合并后的跨度仍超限 → 400，历史数据不能借改期继续超限
        mockMvc.perform(put("/api/trips/" + legacy.getId())
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "endDate", start.plusYears(3).toString()))))
                .andExpect(status().isBadRequest());
    }

    private MvcResult addItem(Account account, long tripId, int dayIndex, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/trips/" + tripId + "/itinerary")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dayIndex", dayIndex,
                                "itemType", "attraction",
                                "title", "行程项 " + dayIndex))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }
}