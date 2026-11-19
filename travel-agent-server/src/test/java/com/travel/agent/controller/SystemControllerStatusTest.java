package com.travel.agent.controller;

import com.travel.agent.config.AmapProperties;
import com.travel.agent.config.RateLimitProperties;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.llm.LlmRuntimeStatus;
import com.travel.agent.service.AmapDataService;
import com.travel.agent.service.InvoiceTextExtractor;
import com.travel.agent.service.LlmQuotaService;
import com.travel.agent.service.PriceReferenceService;
import com.travel.agent.service.RouteService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2.2 验收 P2/P5：{@code /api/system/status} 的自报口径。
 *
 * <p>钉死两条：
 * <ul>
 *   <li>配了高德但最近一次真实调用失败（无效 Key）时，{@code routeProvider} 必须是 estimated，
 *       且 hint 不能宣称「使用高德真实路径」；</li>
 *   <li>{@code llmConfigured} 与 {@code llmVerified} 分离：配置了 Key 不代表最近一次调用成功。</li>
 * </ul>
 */
class SystemControllerStatusTest {

    private final LlmProperties llmProperties = mock(LlmProperties.class);
    private final InvoiceTextExtractor invoiceTextExtractor = mock(InvoiceTextExtractor.class);
    private final RateLimitProperties rateLimitProperties = mock(RateLimitProperties.class);
    private final LlmQuotaService llmQuotaService = mock(LlmQuotaService.class);
    private final AmapProperties amapProperties = mock(AmapProperties.class);
    private final LlmRuntimeStatus llmRuntimeStatus = new LlmRuntimeStatus();

    private SystemController controller(AmapDataService amap) {
        when(llmProperties.getQuotaDailyLimit()).thenReturn(0);
        when(amapProperties.usable()).thenReturn(false);
        return new SystemController(llmProperties, invoiceTextExtractor, rateLimitProperties,
                llmQuotaService, amapProperties, new RouteService(amap), llmRuntimeStatus,
                new PriceReferenceService());
    }

    private static AmapDataService amap(boolean configured, boolean verified, String lastError) {
        AmapDataService amap = mock(AmapDataService.class);
        when(amap.routeAvailable()).thenReturn(configured);
        when(amap.routeVerified()).thenReturn(verified);
        when(amap.routeLastError()).thenReturn(lastError);
        return amap;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(SystemController controller) {
        return (Map<String, Object>) controller.status().getData();
    }

    @Test
    void configuredButUnverifiedAmapIsReportedAsEstimatedWithHonestHint() {
        Map<String, Object> data = data(controller(amap(true, false, null)));

        // routeProvider 取值域保持 amap|estimated，不新增枚举值（前端 Health 页与既有测试依赖）
        assertThat(data.get("routeProvider")).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(data.get("routeConfigured")).isEqualTo(true);
        assertThat(data.get("amapVerified")).isEqualTo(false);
        assertThat(data.get("routeLastError")).isNull();
        assertThat((String) data.get("routeHint")).contains("尚未验证").doesNotContain("使用高德真实路径规划");
    }

    @Test
    void failedRealCallIsNeverReportedAsAmapAndErrorCarriesInfocode() {
        Map<String, Object> data = data(controller(amap(true, false, "infocode=10001, info=INVALID_USER_KEY")));

        assertThat(data.get("routeProvider")).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(data.get("routeConfigured")).isEqualTo(true);
        assertThat(data.get("amapVerified")).isEqualTo(false);
        assertThat((String) data.get("routeLastError")).contains("10001").contains("INVALID_USER_KEY");
        assertThat((String) data.get("routeHint")).contains("10001").doesNotContain("使用高德真实路径规划");
    }

    @Test
    void verifiedAmapReportsAmapAndRealPathHint() {
        Map<String, Object> data = data(controller(amap(true, true, null)));

        assertThat(data.get("routeProvider")).isEqualTo(RouteService.SOURCE_AMAP);
        assertThat(data.get("routeConfigured")).isEqualTo(true);
        assertThat(data.get("amapVerified")).isEqualTo(true);
        assertThat((String) data.get("routeHint")).contains("高德真实路径规划");
    }

    @Test
    void unconfiguredAmapReportsEstimatedWithConfigHint() {
        Map<String, Object> data = data(controller(amap(false, false, null)));

        assertThat(data.get("routeProvider")).isEqualTo(RouteService.SOURCE_ESTIMATED);
        assertThat(data.get("routeConfigured")).isEqualTo(false);
        assertThat(data.get("amapVerified")).isEqualTo(false);
        assertThat((String) data.get("routeHint")).contains("未配置");
    }

    @Test
    void llmVerifiedIsSeparatedFromConfigured() {
        when(llmProperties.usable()).thenReturn(true);
        when(llmProperties.getModel()).thenReturn("test-model");
        SystemController controller = controller(amap(false, false, null));

        // 配了 Key 但本次进程还没有一次成功调用：configured=true，verified=false
        assertThat(data(controller).get("llmConfigured")).isEqualTo(true);
        assertThat(data(controller).get("llmVerified")).isEqualTo(false);

        llmRuntimeStatus.recordSuccess();
        assertThat(data(controller).get("llmVerified")).isEqualTo(true);

        llmRuntimeStatus.recordFailure();
        assertThat(data(controller).get("llmVerified")).isEqualTo(false);
    }
}
