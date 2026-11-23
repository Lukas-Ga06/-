package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.PendingPlanRepository;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 数据源「不可降级地真实」：真实天气拿不到时，<b>依赖天气的行程修改必须被明确拒绝</b>。
 *
 * <p>场景：用户说「明天下雨，把户外景点换掉」。天气服务不可用（这里把预报地址指向必然拒绝连接的
 * 127.0.0.1:1，并关掉测试 profile 的模拟开关）。正确行为是：
 * <ol>
 *   <li>HTTP 200 + {@code status=FAILED}，message 说明「没拿到真实天气（原因）」「没有生成修改方案」；</li>
 *   <li>不生成待确认方案（pending_plan 里没有该用户的行），行程一条不改；</li>
 *   <li>不偷偷用模拟天气改行程——这正是本次要消除的不诚实。</li>
 * </ol>
 */
@TestPropertySource(properties = {
        "travel.weather.simulation=false",
        "travel.weather.forecast-base-url=http://127.0.0.1:1/v1/forecast",
        "travel.weather.geocode-base-url=http://127.0.0.1:1/v1/search"
})
class WeatherDependentModificationTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private PendingPlanRepository pendingPlanRepository;

    @Test
    @DisplayName("真实天气不可用 + 消息依赖天气 → 明确拒绝，不生成待确认方案")
    void weatherDependentModificationIsRejectedWithoutPendingPlan() throws Exception {
        Account account = registerNew();
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("雨天改行程");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        Trip trip = tripService.create(account.id(), request);
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                1, ItineraryItem.TYPE_ATTRACTION, "外滩", "10:00", "11:30",
                BigDecimal.ZERO, 31.23, 121.47, List.of("室外"), "外滩观景"));

        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"明天下雨，把户外景点换掉\",\"tripId\":" + trip.getId() + "}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = body(result).path("data");
        assertThat(data.path("status").asText()).isEqualTo(AgentDtos.STATUS_FAILED);
        String message = data.path("message").asText();
        assertThat(message)
                .contains("真实天气")
                .contains("没有生成修改方案")
                .contains("上海")
                // 原因要如实写出来（连不上 ≠ 超出预报窗口 ≠ 城市无法定位）
                .contains("网络不可达");
        // 没有方案就没有 planId / 确认请求，前端不会出现「确认修改」按钮
        assertThat(data.path("planId").isMissingNode() || data.path("planId").isNull()).isTrue();
        assertThat(data.path("confirmation").isMissingNode() || data.path("confirmation").isNull()).isTrue();

        // 待确认方案落库表里不能有该用户的记录：失败必须是「没有生成方案」，不是「生成了没返回」
        assertThat(pendingPlanRepository.findAll())
                .noneMatch(record -> record.getUserId() != null && record.getUserId() == account.id());
    }
}