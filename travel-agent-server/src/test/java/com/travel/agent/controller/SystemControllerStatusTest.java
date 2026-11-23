package com.travel.agent.controller;

import com.travel.agent.config.AmapProperties;
import com.travel.agent.config.RateLimitProperties;
import com.travel.agent.llm.LlmModelProbe;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.llm.LlmRuntimeStatus;
import com.travel.agent.mcp.AmapMcpAdapter;
import com.travel.agent.mcp.McpToolBridge;
import com.travel.agent.service.AmapDataService;
import com.travel.agent.service.LlmQuotaService;
import com.travel.agent.service.PriceReferenceService;
import com.travel.agent.service.RouteService;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2.2 验收 P2/P5 + P0-A：{@code /api/system/status} 的自报口径。
 *
 * <p>钉死：
 * <ul>
 *   <li>配了高德但最近一次真实调用失败（无效 Key）时，{@code routeProvider} 必须是 estimated，
 *       且 hint 不能宣称「使用高德真实路径」；</li>
 *   <li>{@code llmConfigured} 与 {@code llmVerified} 分离：配置了 Key 不代表最近一次调用成功；</li>
 *   <li>P0-A：MCP 接入状态（启用/连接/工具数/每 server 最近错误）与模型探测结果
 *       （可用/失败/尚未校验）如实暴露，且不泄露密钥。</li>
 * </ul>
 */
class SystemControllerStatusTest {

    private final LlmProperties llmProperties = mock(LlmProperties.class);
    private final RateLimitProperties rateLimitProperties = mock(RateLimitProperties.class);
    private final LlmQuotaService llmQuotaService = mock(LlmQuotaService.class);
    private final AmapProperties amapProperties = mock(AmapProperties.class);
    private final LlmRuntimeStatus llmRuntimeStatus = new LlmRuntimeStatus();
    private final McpToolBridge mcpToolBridge = mock(McpToolBridge.class);
    private final AmapMcpAdapter amapMcpAdapter = new AmapMcpAdapter();
    private final ToolRegistry toolRegistry = new ToolRegistry(List.of());
    private final LlmModelProbe llmModelProbe = mock(LlmModelProbe.class);

    private SystemController controller(AmapDataService amap) {
        when(llmProperties.getQuotaDailyLimit()).thenReturn(0);
        when(amapProperties.usable()).thenReturn(false);
        when(mcpToolBridge.status()).thenReturn(
                new McpToolBridge.McpStatusSnapshot(false, false, 0, List.of(), null));
        when(llmModelProbe.status()).thenReturn(new LlmModelProbe.ModelCheck(null, null, false));
        return new SystemController(llmProperties, rateLimitProperties,
                llmQuotaService, amapProperties, new RouteService(amap), llmRuntimeStatus,
                new PriceReferenceService(), mcpToolBridge, amapMcpAdapter, toolRegistry, llmModelProbe);
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

        // routeProvider 取值域保持 amap|estimated，不新增枚举值（前端系统状态卡与既有测试依赖）
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

    @Test
    @DisplayName("P0-A：MCP 启用且接入成功时如实报告连接、工具数与逐 server 状态")
    @SuppressWarnings("unchecked")
    void reportsMcpConnectedSnapshot() {
        SystemController controller = controller(amap(false, false, null));
        when(mcpToolBridge.status()).thenReturn(new McpToolBridge.McpStatusSnapshot(
                true, true, 3,
                List.of(new McpToolBridge.McpServerStatus("amap", true, 3, null)), null));

        Map<String, Object> data = data(controller);

        assertThat(data.get("mcpEnabled")).isEqualTo(true);
        assertThat(data.get("mcpConnected")).isEqualTo(true);
        assertThat(data.get("mcpToolCount")).isEqualTo(3);
        assertThat(data.get("mcpLastError")).isNull();
        assertThat((String) data.get("mcpHint")).contains("已接入 3 个外部工具");
        List<Map<String, Object>> servers = (List<Map<String, Object>>) data.get("mcpServers");
        assertThat(servers).singleElement().satisfies(server -> {
            assertThat(server.get("name")).isEqualTo("amap");
            assertThat(server.get("connected")).isEqualTo(true);
            assertThat(server.get("toolCount")).isEqualTo(3);
            assertThat(server.get("lastError")).isNull();
        });
        assertThat(data.get("amapMcpToolCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("P0-A：MCP 接入失败时 mcpConnected=false，最近错误可见且 hint 不宣称可用")
    void reportsMcpFailure() {
        SystemController controller = controller(amap(false, false, null));
        when(mcpToolBridge.status()).thenReturn(new McpToolBridge.McpStatusSnapshot(
                true, true, 0,
                List.of(new McpToolBridge.McpServerStatus("amap", false, 0, "connect timed out")),
                "connect timed out"));

        Map<String, Object> data = data(controller);

        assertThat(data.get("mcpEnabled")).isEqualTo(true);
        assertThat(data.get("mcpConnected")).isEqualTo(false);
        assertThat(data.get("mcpToolCount")).isEqualTo(0);
        assertThat(data.get("mcpLastError")).isEqualTo("connect timed out");
        assertThat((String) data.get("mcpHint")).contains("接入失败").contains("connect timed out");
    }

    @Test
    @DisplayName("P0-A：模型探测结论（不可用原因）与 provider host 如实暴露，不泄露 Key")
    void reportsModelProbeResult() {
        when(llmProperties.usable()).thenReturn(true);
        when(llmProperties.getModel()).thenReturn("deepseek-flash");
        when(llmProperties.providerHost()).thenReturn("api.deepseek.com");
        SystemController controller = controller(amap(false, false, null));
        when(llmModelProbe.status()).thenReturn(new LlmModelProbe.ModelCheck(
                Boolean.FALSE, "配置的模型「deepseek-flash」不在模型清单中；可用模型：deepseek-v4-pro", false));

        Map<String, Object> data = data(controller);

        assertThat(data.get("llmProvider")).isEqualTo("api.deepseek.com");
        assertThat(data.get("llmModelAvailable")).isEqualTo(false);
        assertThat((String) data.get("llmModelError")).contains("不在模型清单中").contains("deepseek-v4-pro");
        assertThat(data.get("llmModelCheckPending")).isEqualTo(false);
        assertThat(data.toString()).doesNotContain("sk-").doesNotContain("apiKey").doesNotContain("api-key");
    }

    @Test
    @DisplayName("P0-A：探测进行中 llmModelCheckPending=true、llmModelAvailable=null（未校验不是可用）")
    void reportsPendingModelCheck() {
        when(llmProperties.usable()).thenReturn(true);
        when(llmProperties.providerHost()).thenReturn("api.deepseek.com");
        SystemController controller = controller(amap(false, false, null));
        when(llmModelProbe.status()).thenReturn(new LlmModelProbe.ModelCheck(null, null, true));

        Map<String, Object> data = data(controller);

        assertThat(data.get("llmModelAvailable")).isNull();
        assertThat(data.get("llmModelCheckPending")).isEqualTo(true);
    }

    @Test
    @DisplayName("P0-A：LLM 未配置时模型可用性保持 null（未校验），不产生伪结论")
    void modelAvailabilityStaysNullWhenLlmNotConfigured() {
        when(llmProperties.usable()).thenReturn(false);

        Map<String, Object> data = data(controller(amap(false, false, null)));

        assertThat(data.get("llmProvider")).isNull();
        assertThat(data.get("llmModelAvailable")).isNull();
        assertThat(data.get("llmModelCheckPending")).isEqualTo(false);
    }
}
