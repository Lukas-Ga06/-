package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * V2.2 验收 P2/P6：路线能力自报口径与缓存 TTL。
 *
 * <p>验收报告暴露的两个事实级缺陷：
 * <ul>
 *   <li>P2：配了无效 Key 时 {@code routeProvider} 仍报 amap（实际全 estimated）——这里钉死
 *       「无效 Key / 最近一次真实调用失败 → 绝不报 amap」；</li>
 *   <li>P6：一次 QPS 限流失败产生的 estimated 结果被缓存 10 分钟，高德恢复后仍钉死估算——
 *       这里用可控时钟证明 estimated 只缓存 60 秒、amap 仍缓存 10 分钟。</li>
 * </ul>
 * 用真实 {@link AmapDataService} + MockRestServiceServer：状态机来自真实解析逻辑，不是 mock 出来的。
 */
class RouteServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DRIVE_JSON = """
            {"status":"1","route":{"paths":[
              {"distance":"12345","duration":"1800",
               "steps":[{"polyline":"121.490317,31.222771;121.500000,31.240000;121.510000,31.250000"}]}]}}
            """;
    private static final String INVALID_KEY_JSON = """
            {"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}
            """;
    private static final double FROM_LAT = 31.2304;
    private static final double FROM_LNG = 121.4737;
    private static final double TO_LAT = 31.2397;
    private static final double TO_LNG = 121.4998;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ToolRegistry> emptyTools() {
        ObjectProvider<ToolRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new ToolRegistry(List.of()));
        return provider;
    }

    /** 走真实 AmapDataService + MockRestServiceServer 的测试装配（key 是假值，不会写进任何仓库文件） */
    private record Fixture(AmapDataService amap, MockRestServiceServer server) {
    }

    private static Fixture fixture(String key) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AmapDataService amap = new AmapDataService(emptyTools(), MAPPER, key, builder.build());
        return new Fixture(amap, server);
    }

    @Test
    void invalidKeyNeverReportedAsAmap() {
        Fixture f = fixture("unit-test-invalid-key");
        f.server.expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(INVALID_KEY_JSON, MediaType.APPLICATION_JSON));
        RouteService routeService = new RouteService(f.amap);

        RouteInfo info = routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG);

        assertThat(info.source()).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(info.polyline()).isEmpty();      // 降级结果不给假几何
        assertThat(routeService.provider()).isEqualTo(RouteService.SOURCE_ESTIMATED); // P2 核心断言
        assertThat(routeService.configured()).isTrue();   // 配置里确实有 Key
        assertThat(routeService.verified()).isFalse();    // 但一次都没验证成功
        assertThat(routeService.lastError()).contains("10001").doesNotContain("unit-test-invalid-key");
        f.server.verify();
    }

    @Test
    void configuredButNotYetCalledIsReportedAsEstimated() {
        Fixture f = fixture("unit-test-invalid-key");
        RouteService routeService = new RouteService(f.amap);

        // 启动后首次真实调用前：配置了也不能宣称 amap（「配置了但尚未验证」）
        assertThat(routeService.configured()).isTrue();
        assertThat(routeService.verified()).isFalse();
        assertThat(routeService.provider()).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(routeService.lastError()).isNull();
        f.server.verify(); // 这里不应该发生任何 HTTP 调用
    }

    @Test
    void successfulRealCallSwitchesProviderToAmap() {
        Fixture f = fixture("unit-test-valid-key");
        f.server.expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(DRIVE_JSON, MediaType.APPLICATION_JSON));
        RouteService routeService = new RouteService(f.amap);

        RouteInfo info = routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG);

        assertThat(info.source()).isEqualTo(RouteService.SOURCE_AMAP);
        assertThat(info.polyline()).hasSize(3);   // 真实几何从 AmapRoute 透传到 RouteInfo
        assertThat(routeService.provider()).isEqualTo(RouteService.SOURCE_AMAP);
        assertThat(routeService.verified()).isTrue();
        assertThat(routeService.lastError()).isNull();
        f.server.verify();
    }

    @Test
    void estimatedResultExpiresAfterSixtySeconds() {
        AtomicLong now = new AtomicLong(0);
        Fixture f = fixture("unit-test-invalid-key");
        f.server.expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(INVALID_KEY_JSON, MediaType.APPLICATION_JSON));
        f.server.expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(DRIVE_JSON, MediaType.APPLICATION_JSON));
        RouteService routeService = new RouteService(f.amap, now::get);

        // t=0：第一次调用失败 → 降级并缓存 estimated
        assertThat(routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG).source())
                .isEqualTo(RouteService.SOURCE_ESTIMATED);

        // t=59s：短 TTL 内仍命中缓存（不会重打外部接口；若打了会返回 amap，断言失败）
        now.set(59_000L);
        assertThat(routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG).source())
                .isEqualTo(RouteService.SOURCE_ESTIMATED);

        // t=61s：estimated 的 60 秒短 TTL 已过 → 重新调用高德 → 恢复真实路径。
        // 如果 estimated 沿用 10 分钟 TTL，这里仍然是缓存里的 estimated（P6 的缺陷）。
        now.set(61_000L);
        RouteInfo recovered = routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG);
        assertThat(recovered.source()).isEqualTo(RouteService.SOURCE_AMAP);
        assertThat(routeService.provider()).isEqualTo(RouteService.SOURCE_AMAP);
        assertThat(RouteService.ESTIMATED_CACHE_TTL_MS).isLessThan(RouteService.CACHE_TTL_MS);
        f.server.verify();
    }

    @Test
    void amapResultKeepsTenMinuteTtl() {
        AtomicLong now = new AtomicLong(0);
        Fixture f = fixture("unit-test-valid-key");
        f.server.expect(requestTo(containsString("/v3/direction/driving")))
                .andRespond(withSuccess(DRIVE_JSON, MediaType.APPLICATION_JSON));
        RouteService routeService = new RouteService(f.amap, now::get);

        assertThat(routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG).source())
                .isEqualTo(RouteService.SOURCE_AMAP);

        // 5 分钟后仍应是缓存命中：amap 结果按 10 分钟缓存（mock 只有一条期望，再请求会失败）
        now.set(5 * 60 * 1000L);
        RouteInfo cached = routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG);
        assertThat(cached.source()).isEqualTo(RouteService.SOURCE_AMAP);
        assertThat(cached.polyline()).hasSize(3);
        f.server.verify();
    }

    @Test
    void unconfiguredServiceNeverReportsAmap() {
        RouteService routeService = new RouteService();   // 无 AmapDataService：纯估算场景

        RouteInfo info = routeService.route(FROM_LAT, FROM_LNG, TO_LAT, TO_LNG);

        assertThat(info.source()).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(routeService.configured()).isFalse();
        assertThat(routeService.provider()).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(routeService.lastError()).isNull();
    }
}
