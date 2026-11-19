package com.travel.agent;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryRouteService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.RouteService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V2.2 FR-05 / FR-07：路线数据来源标记与每日路线段。
 *
 * <p>测试环境不配置高德 Key（无真实外呼），因此：
 * <ul>
 *   <li>RouteService 必须如实标记 {@code source=estimated}，且降级路径行为与升级前一致；</li>
 *   <li>路线段端点按天输出相邻坐标条目之间的距离/时长/来源，无坐标条目自动跳过；</li>
 *   <li>端点做归属校验，他人行程 404。</li>
 * </ul>
 */
class ItineraryRouteLegsTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;
    @Autowired
    private ItineraryService itineraryService;
    @Autowired
    private ItineraryRouteService itineraryRouteService;
    @Autowired
    private RouteService routeService;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("路线段测试");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private ItineraryItem addItem(Trip trip, int day, String title, String start, String end,
                                  Double lat, Double lng) {
        return itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                day, ItineraryItem.TYPE_ATTRACTION, title, start, end, BigDecimal.ZERO, lat, lng, null, null));
    }

    @Test
    @DisplayName("无高德配置时路线来源标记为 estimated（不把估算伪装成实时数据）")
    void routeSourceIsHonestWithoutProvider() {
        var info = routeService.route(31.2304, 121.4737, 31.2397, 121.4998);
        assertThat(info.source()).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(info.real()).isFalse();
        assertThat(info.distanceKm()).isGreaterThan(0);
        assertThat(info.driveMinutes()).isGreaterThan(0);
        assertThat(routeService.provider()).isEqualTo(RouteService.SOURCE_ESTIMATED);
    }

    @Test
    @DisplayName("同一区间的重复计算命中缓存且结果一致（缓存不改变口径）")
    void repeatedRouteIsCachedAndStable() {
        var first = routeService.route(31.2304, 121.4737, 31.2397, 121.4998);
        var second = routeService.route(31.2304, 121.4737, 31.2397, 121.4998);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("路线段按天输出相邻条目；无坐标条目（交通费汇总）自动跳过")
    void routeLegsSkipItemsWithoutCoordinates() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, 1, "外滩", "10:00", "11:30", 31.2397, 121.4998);
        addItem(trip, 1, "豫园", "14:00", "16:00", 31.2270, 121.4921);
        // 交通费条目无坐标：不参与路线
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                1, ItineraryItem.TYPE_TRANSPORT, "当日交通（打车）", "19:45", "20:00",
                BigDecimal.valueOf(30), null, null, null, null));

        List<ItineraryDtos.RouteLegDTO> legs = itineraryRouteService.forTrip(trip.getId());
        assertThat(legs).hasSize(1);
        ItineraryDtos.RouteLegDTO leg = legs.get(0);
        assertThat(leg.getDayIndex()).isEqualTo(1);
        assertThat(leg.getSequence()).isEqualTo(1);
        assertThat(leg.getFromTitle()).isEqualTo("外滩");
        assertThat(leg.getToTitle()).isEqualTo("豫园");
        assertThat(leg.getDistanceKm()).isGreaterThan(0);
        assertThat(leg.getSource()).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(leg.getTaxiCost()).isGreaterThan(0);
    }

    @Test
    @DisplayName("GET /api/trips/{id}/route-legs 返回路线段；他人行程 404")
    void routeLegsEndpointRespectsOwnership() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, 1, "外滩", "10:00", "11:30", 31.2397, 121.4998);
        addItem(trip, 2, "豫园", "10:00", "11:30", 31.2270, 121.4921);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/route-legs")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                // 两天各只有一个点位 → 没有相邻段
                .andExpect(jsonPath("$.data.length()").value(0));

        Account stranger = registerNew();
        mockMvc.perform(get("/api/trips/" + trip.getId() + "/route-legs")
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("route-legs 的 polyline 契约：无几何时为空数组，不给假点（前端据此回退两点直连）")
    void routeLegPolylineIsEmptyArrayWithoutGeometry() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        addItem(trip, 1, "外滩", "10:00", "11:30", 31.2397, 121.4998);
        addItem(trip, 1, "豫园", "14:00", "16:00", 31.2270, 121.4921);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/route-legs")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].source").value(RouteService.SOURCE_ESTIMATED))
                // 契约：polyline 始终是数组（WGS-84 [lat,lng] 的点序列）；估算段没有几何 → []
                .andExpect(jsonPath("$.data[0].polyline").isArray())
                .andExpect(jsonPath("$.data[0].polyline").isEmpty());
    }

    @Test
    @DisplayName("系统状态如实上报路线来源")
    void systemStatusReportsRouteProvider() throws Exception {
        Account account = registerNew();
        mockMvc.perform(get("/api/system/status").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeProvider").value(RouteService.SOURCE_ESTIMATED))
                // P2：配置态 / 验证态 / 失败原因与 provider 口径一致
                .andExpect(jsonPath("$.data.routeConfigured").value(false))
                .andExpect(jsonPath("$.data.amapVerified").value(false))
                .andExpect(jsonPath("$.data.routeHint").isNotEmpty());
    }
}
