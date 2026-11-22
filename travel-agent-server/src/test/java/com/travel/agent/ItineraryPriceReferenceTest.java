package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-006 前端契约的 HTTP 级验收：{@code GET /api/trips/{id}/itinerary} 的每个 item 带
 * {@code price}（参考价 + 来源 + 深链），且既有 {@code cost} 语义不变。
 *
 * <p>这里只断言形状与口径，不打外部网络：
 * <ul>
 *   <li>酒店（上海，已实测 cityId=2）→ 携程 canonical 链接 + 高德链接；</li>
 *   <li>免费条目（cost=0）→ {@code price: null}；</li>
 *   <li>所有价格声明都是「参考价 / 本地估算 / 非实时」，绝不出现「实时/有房/可订为事实」的措辞；</li>
 *   <li>新增/修改的返回形状与列表一致（同一套前端渲染代码）；</li>
 *   <li>{@code /api/system/status} 同时如实上报 routeProvider 与 priceProvider，互不顶替。</li>
 * </ul>
 */
class ItineraryPriceReferenceTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;
    @Autowired
    private ItineraryService itineraryService;

    private Trip newTrip(long userId, String destination) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("参考价测试");
        request.setDestination(destination);
        request.setStartDate("2026-11-20");
        request.setEndDate("2026-11-22");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private ItineraryItem addItem(Trip trip, String type, String title, String start, String end,
                                  BigDecimal cost, Double lat, Double lng) {
        return itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                1, type, title, start, end, cost, lat, lng, null, null));
    }

    @Test
    @DisplayName("列表：酒店 item 带参考价/来源/携程+高德深链，cost=0 的条目 price 为 null")
    void itineraryItemsCarryPriceReference() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id(), "上海");
        addItem(trip, ItineraryItem.TYPE_ATTRACTION, "外滩", "10:00", "11:30", BigDecimal.ZERO, 31.2397, 121.4998);
        addItem(trip, ItineraryItem.TYPE_HOTEL, "外滩茂悦大酒店", "15:00", "17:00",
                new BigDecimal("520.00"), 31.2304, 121.4737);

        MvcResult result = mockMvc.perform(get("/api/trips/" + trip.getId() + "/itinerary")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                // 免费条目：price 为 null（不渲染 ¥0 参考价），cost 仍保留 0
                .andExpect(jsonPath("$.data[0].title").value("外滩"))
                .andExpect(jsonPath("$.data[0].cost").exists())
                .andExpect(jsonPath("$.data[0].price").value(nullValue()))
                // 酒店：价格自报字段固定
                .andExpect(jsonPath("$.data[1].price.currency").value("CNY"))
                .andExpect(jsonPath("$.data[1].price.kind").value("reference"))
                .andExpect(jsonPath("$.data[1].price.label").value("参考价"))
                .andExpect(jsonPath("$.data[1].price.source").value("local-estimate"))
                .andExpect(jsonPath("$.data[1].price.sourceLabel").value("本地估算"))
                .andExpect(jsonPath("$.data[1].price.disclaimer").value(containsString("非实时报价")))
                // 携程：上海 cityId=2，日期取行程 startDate/endDate
                .andExpect(jsonPath("$.data[1].price.links.length()").value(2))
                .andExpect(jsonPath("$.data[1].price.links[0].provider").value("ctrip"))
                .andExpect(jsonPath("$.data[1].price.links[0].kind").value("price"))
                .andExpect(jsonPath("$.data[1].price.links[0].url").value(
                        "https://hotels.ctrip.com/hotels/list?flexType=1&cityId=2&provinceId=0"
                                + "&districtId=0&countryId=1&checkin=2026-11-20&checkout=2026-11-22"))
                // 高德：经度在前、coordinate=wgs84 必须保留
                .andExpect(jsonPath("$.data[1].price.links[1].provider").value("amap"))
                .andExpect(jsonPath("$.data[1].price.links[1].kind").value("map"))
                .andExpect(jsonPath("$.data[1].price.links[1].url").value(containsString(
                        "position=121.4737,31.2304")))
                .andExpect(jsonPath("$.data[1].price.links[1].url").value(containsString("coordinate=wgs84")))
                .andReturn();

        JsonNode data = body(result).path("data");
        // cost 语义不丢：价格只是 cost 的展示层包装，amount 与 cost 完全一致
        assertThat(data.get(0).path("cost").decimalValue()).isEqualByComparingTo("0");
        assertThat(data.get(1).path("cost").decimalValue()).isEqualByComparingTo("520.00");
        assertThat(data.get(1).path("price").path("amount").decimalValue()).isEqualByComparingTo("520.00");

        String raw = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // 大众点评实测会 302 到登录/验证页，禁止出现在任何深链里
        assertThat(raw).doesNotContain("dianping", "大众点评");
        // 不许把参考价写成有房/可订的事实
        assertThat(raw).doesNotContain("有房");
    }

    @Test
    @DisplayName("新增与修改返回的 item 与列表同形状：都带 price，改成 0 后 price 变 null")
    void addAndUpdateResponsesCarryPriceConsistently() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id(), "上海");

        MvcResult created = mockMvc.perform(post("/api/trips/" + trip.getId() + "/itinerary")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayIndex\":1,\"itemType\":\"hotel\",\"title\":\"外滩茂悦大酒店\","
                                + "\"startTime\":\"15:00\",\"endTime\":\"17:00\",\"cost\":300,"
                                + "\"latitude\":31.2304,\"longitude\":121.4737}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price.kind").value("reference"))
                .andExpect(jsonPath("$.data.price.source").value("local-estimate"))
                .andExpect(jsonPath("$.data.price.links[0].provider").value("ctrip"))
                .andExpect(jsonPath("$.data.price.links[0].url").value(containsString("cityId=2")))
                .andReturn();

        long itemId = body(created).path("data").path("id").asLong();

        MvcResult updated = mockMvc.perform(put("/api/itinerary/" + itemId)
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cost\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(nullValue()))
                .andReturn();
        assertThat(body(updated).path("data").path("cost").decimalValue()).isEqualByComparingTo("0");

        // 重新读列表：同样是 null（列表与写接口口径一致）
        mockMvc.perform(get("/api/trips/" + trip.getId() + "/itinerary")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].price").value(nullValue()));
    }

    @Test
    @DisplayName("系统状态：priceProvider=reference-estimate，且不顶替 routeProvider 的 estimated 口径")
    void systemStatusReportsPriceProvider() throws Exception {
        Account account = registerNew();
        mockMvc.perform(get("/api/system/status").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priceProvider").value("reference-estimate"))
                .andExpect(jsonPath("$.data.priceHint").value(containsString("参考")))
                .andExpect(jsonPath("$.data.priceHint").value(containsString("未接入实时房态")))
                // 路线来源仍是既有口径（测试环境无高德 Key → estimated），两类能力各报各的
                .andExpect(jsonPath("$.data.routeProvider").value("estimated"));
    }

    @Test
    @DisplayName("目的地不在实测城市表内：酒店只给高德链接，不给携程（不猜 cityId）")
    void unmappedCityGetsNoCtripLink() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id(), "成都");
        addItem(trip, ItineraryItem.TYPE_HOTEL, "春熙路酒店", "15:00", "17:00",
                new BigDecimal("480"), 30.6570, 104.0657);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/itinerary")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].price.links.length()").value(1))
                .andExpect(jsonPath("$.data[0].price.links[0].provider").value("amap"))
                .andExpect(jsonPath("$.data[0].price.links[0].url").value(containsString("position=104.0657,30.657")));
    }

    @Test
    @DisplayName("拿不到行程上下文时（无坐标、非酒店）links 是空数组而不是 null")
    void emptyLinksIsArray() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id(), "上海");
        addItem(trip, ItineraryItem.TYPE_TRANSPORT, "当日交通（打车）", "19:45", "20:00",
                BigDecimal.valueOf(30), null, null);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/itinerary")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].price.kind").value("reference"))
                .andExpect(jsonPath("$.data[0].price.links").isArray())
                .andExpect(jsonPath("$.data[0].price.links").isEmpty());
    }

    @Test
    @DisplayName("他人行程的 itinerary 仍然 404（价格不改变归属校验）")
    void ownershipStillEnforced() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id(), "上海");
        addItem(trip, ItineraryItem.TYPE_HOTEL, "酒店", "15:00", "17:00",
                new BigDecimal("520"), 31.2304, 121.4737);

        Account stranger = registerNew();
        mockMvc.perform(get("/api/trips/" + trip.getId() + "/itinerary")
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
    }
}