package com.travel.agent.observability;

import com.travel.agent.config.McpProperties;
import com.travel.agent.mcp.McpToolBridge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 治理指标（{@code mcp.server.*}）的命名、tag、NaN 纪律与「抓取时现读」契约。
 *
 * <p>为什么不测「埋点处累加计数器」：这些计数的权威在 {@code McpGovernor}，Gauge 只是每次抓取现读闸门快照。
 * 因此这里用 SimpleMeterRegistry + 可切换的快照供给验证三件事：
 * <ol>
 *   <li>桥接不存在/未启用/未启动/server 不在快照时采样是 <b>NaN</b>，不是 0（「查不到」与「真的是 0」必须能区分）；</li>
 *   <li>快照在时值与 tag 正确，熔断三态只有命中的那条是 1；</li>
 *   <li>两次抓取之间快照变化能读到新值——不是注册时固化（否则指标会像「投递器顺手记数」那样冻结）。</li>
 * </ol>
 */
class McpGovernanceMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double gauge(String metric, String server) {
        return registry.find(metric).tag("server", server).gauge().value();
    }

    private double circuit(String server, String state) {
        return registry.find(TravelMetrics.MCP_SERVER_CIRCUIT)
                .tag("server", server).tag("state", state).gauge().value();
    }

    private double enabled() {
        return registry.find(TravelMetrics.MCP_ENABLED).gauge().value();
    }

    // ==== 构造工具 ====

    private static McpProperties props(boolean enabled, String... servers) {
        McpProperties properties = new McpProperties();
        properties.setEnabled(enabled);
        List<McpProperties.Server> list = Arrays.stream(servers).map(name -> {
            McpProperties.Server server = new McpProperties.Server();
            server.setName(name);
            return server;
        }).toList();
        properties.setServers(list);
        return properties;
    }

    private static McpToolBridge.McpServerStatus row(String name, boolean connected, int toolCount,
                                                    int calls, int failures, int rejected, String circuit) {
        return new McpToolBridge.McpServerStatus(name, connected, toolCount, null, calls, failures, rejected, circuit);
    }

    /** 带配额窗口的状态行：{@code callsThisMinute/maxCallsPerMinute} 都有值（配额已配置） */
    private static McpToolBridge.McpServerStatus rowWithQuota(String name, int calls, int callsThisMinute,
                                                             int maxCallsPerMinute) {
        return new McpToolBridge.McpServerStatus(name, true, 1, null, calls, 0, 0, "closed",
                callsThisMinute, maxCallsPerMinute);
    }

    private static McpToolBridge.McpStatusSnapshot snapshot(McpToolBridge.McpServerStatus... rows) {
        int tools = Arrays.stream(rows).filter(McpToolBridge.McpServerStatus::connected)
                .mapToInt(McpToolBridge.McpServerStatus::toolCount).sum();
        return new McpToolBridge.McpStatusSnapshot(true, true, tools, List.of(rows), null);
    }

    /** 桥接不存在（未装配）时的指标组件 */
    private void metricsWithoutBridge(McpProperties properties) {
        new McpGovernanceMetrics(registry, properties, () -> null);
    }

    /** 每次抓取都重新问一遍供给函数（模拟「桥接快照在两次抓取之间变化」） */
    private void metricsWithBridge(McpProperties properties, Supplier<McpToolBridge.McpStatusSnapshot> snapshots) {
        McpToolBridge bridge = mock(McpToolBridge.class);
        when(bridge.status()).thenAnswer(invocation -> snapshots.get());
        new McpGovernanceMetrics(registry, properties, () -> bridge);
    }

    // ==== 1) 不可观测 → NaN，绝不伪造 0 ====

    @Test
    @DisplayName("桥接不存在（未装配）：逐 server 序列全部 NaN，只保留配置期已知的开关值")
    void missingBridgeReportsNaN() {
        metricsWithoutBridge(props(true, "amap"));

        assertThat(enabled()).isEqualTo(1.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_CONNECTED, "amap")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_TOOLS, "amap")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_FAILURES, "amap")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_REJECTED, "amap")).isNaN();
        // 三态同时 NaN：不能被读成 closed（否则「读不到状态」= 健康）
        assertThat(circuit("amap", "closed")).isNaN();
        assertThat(circuit("amap", "open")).isNaN();
        assertThat(circuit("amap", "half_open")).isNaN();
        // 配额窗口同样是「不可知」：不能报 0 次/分钟（那是「没配额」被读成「很闲」）
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS_THIS_MINUTE, "amap")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_QUOTA, "amap")).isNaN();
    }

    @Test
    @DisplayName("MCP 未启用（enabled=false）：开关报 0，逐 server 序列仍是 NaN")
    void disabledMcpReportsNaNForServerGauges() {
        McpToolBridge.McpStatusSnapshot disabled = new McpToolBridge.McpStatusSnapshot(
                false, false, 0, List.of(row("amap", false, 0, 0, 0, 0, "closed")), null);
        metricsWithBridge(props(false, "amap"), () -> disabled);

        assertThat(enabled()).isZero();
        assertThat(gauge(TravelMetrics.MCP_SERVER_CONNECTED, "amap")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isNaN();
        assertThat(circuit("amap", "closed")).isNaN();
    }

    @Test
    @DisplayName("桥接未启动（running=false）：即使快照里已有行，也报 NaN（启动前的 0 是假事实）")
    void notStartedBridgeReportsNaN() {
        McpToolBridge.McpStatusSnapshot starting = new McpToolBridge.McpStatusSnapshot(
                true, false, 0, List.of(row("amap", false, 0, 0, 0, 0, "closed")), null);
        metricsWithBridge(props(true, "amap"), () -> starting);

        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_CONNECTED, "amap")).isNaN();
    }

    @Test
    @DisplayName("配置里有、快照里没有的 server 报 NaN（不回落成 0 次调用）")
    void serverMissingFromSnapshotReportsNaN() {
        metricsWithBridge(props(true, "amap", "wiki"), () -> snapshot(row("amap", true, 1, 3, 0, 0, "closed")));

        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isEqualTo(3.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "wiki")).isNaN();
        assertThat(circuit("wiki", "open")).isNaN();
    }

    // ==== 2) 值与 tag ====

    @Test
    @DisplayName("快照在时：值、tag 与序列条数正确（每 server 固定 7 条 + 熔断 3 条）")
    void readsValuesAndRegistersStableTagSets() {
        metricsWithBridge(props(true, "amap", "wiki"), () -> snapshot(
                row("amap", true, 2, 7, 1, 3, "closed"),
                row("wiki", false, 0, 0, 0, 0, "closed")));

        assertThat(enabled()).isEqualTo(1.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_CONNECTED, "amap")).isEqualTo(1.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_TOOLS, "amap")).isEqualTo(2.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isEqualTo(7.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_FAILURES, "amap")).isEqualTo(1.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_REJECTED, "amap")).isEqualTo(3.0);
        // 接入失败的 server：connected 是 0（这是真实事实），计数确实是 0 次
        assertThat(gauge(TravelMetrics.MCP_SERVER_CONNECTED, "wiki")).isZero();
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "wiki")).isZero();

        assertThat(registry.find(TravelMetrics.MCP_SERVER_CALLS).gauges()).hasSize(2);
        assertThat(registry.find(TravelMetrics.MCP_SERVER_CIRCUIT).gauges()).hasSize(6);
        assertThat(registry.find(TravelMetrics.MCP_SERVER_CIRCUIT).tag("server", "amap").gauges()).hasSize(3);
    }

    @Test
    @DisplayName("配额窗口：已配置时有值，未配置（null）时两条序列都是 NaN，不是 0")
    void exposesQuotaWindowOnlyWhenConfigured() {
        metricsWithBridge(props(true, "quota", "noquota"), () -> snapshot(
                rowWithQuota("quota", 30, 27, 30),
                row("noquota", true, 1, 12, 0, 0, "closed")));

        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS_THIS_MINUTE, "quota")).isEqualTo(27.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_QUOTA, "quota")).isEqualTo(30.0);

        // 未配置配额：闸门的 callsThisMinute 恒为 0，那不是事实，必须 NaN
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS_THIS_MINUTE, "noquota")).isNaN();
        assertThat(gauge(TravelMetrics.MCP_SERVER_QUOTA, "noquota")).isNaN();
        // 累计计数不受影响，仍如实上报
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "noquota")).isEqualTo(12.0);
    }

    @Test
    @DisplayName("熔断三态映射：CLOSED/OPEN/HALF_OPEN 各自只有命中的那条为 1")
    void mapsCircuitStateToExactlyOneHotSeries() {
        metricsWithBridge(props(true, "closed", "open", "half"), () -> snapshot(
                row("closed", true, 1, 0, 0, 0, "closed"),
                row("open", true, 1, 5, 5, 2, "open"),
                row("half", true, 1, 6, 0, 0, "half_open")));

        assertThat(circuit("closed", "closed")).isEqualTo(1.0);
        assertThat(circuit("closed", "open")).isZero();
        assertThat(circuit("closed", "half_open")).isZero();

        assertThat(circuit("open", "open")).isEqualTo(1.0);
        assertThat(circuit("open", "closed")).isZero();
        assertThat(circuit("open", "half_open")).isZero();

        assertThat(circuit("half", "half_open")).isEqualTo(1.0);
        assertThat(circuit("half", "open")).isZero();
        assertThat(circuit("half", "closed")).isZero();

        // 告警表达式依赖这条：==1 只会命中真正处于该状态的 server
        List<String> openServers = registry.find(TravelMetrics.MCP_SERVER_CIRCUIT)
                .tag("state", "open").gauges().stream()
                .filter(gauge -> gauge.value() == 1.0)
                .map(gauge -> gauge.getId().getTag("server"))
                .toList();
        assertThat(openServers).containsExactly("open");
    }

    // ==== 3) 现读语义 ====

    @Test
    @DisplayName("现读而非注册时固化：两次抓取之间闸门计数变化能读到新值")
    void readsLiveValuesOnEveryScrape() {
        AtomicReference<McpToolBridge.McpStatusSnapshot> current =
                new AtomicReference<>(snapshot(row("amap", true, 1, 3, 0, 0, "closed")));
        metricsWithBridge(props(true, "amap"), current::get);

        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isEqualTo(3.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_FAILURES, "amap")).isZero();

        // 中间又发生了 6 次调用、2 次失败，并且熔断被打开
        current.set(snapshot(row("amap", true, 1, 9, 2, 0, "open")));

        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isEqualTo(9.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_FAILURES, "amap")).isEqualTo(2.0);
        assertThat(circuit("amap", "open")).isEqualTo(1.0);
        assertThat(circuit("amap", "closed")).isZero();
    }

    @Test
    @DisplayName("server 名按桥接口径归一：空名回落 unnamed，重复配置只注册一组序列")
    void normalizesBlankAndDuplicateServerNames() {
        McpProperties properties = props(true, "amap", "amap", " ");
        metricsWithBridge(properties, () -> snapshot(row("amap", true, 1, 1, 0, 0, "closed")));

        assertThat(registry.find(TravelMetrics.MCP_SERVER_CALLS).gauges()).hasSize(2);
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, "amap")).isEqualTo(1.0);
        assertThat(gauge(TravelMetrics.MCP_SERVER_CALLS, McpGovernanceMetrics.UNNAMED_SERVER)).isNaN();
    }

    // ==== 4) 对外契约：Prometheus 名与 NaN 的导出形态 ====

    @Test
    @DisplayName("Prometheus 导出名与 NaN：mcp_server_* 名字对得上契约，不可观测写成 NaN 而不是 0")
    void exportsPrometheusNamesAndKeepsNaN() {
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry prometheus =
                new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                        io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        McpToolBridge bridge = mock(McpToolBridge.class);
        // 配置里有 amap 与 wiki，但快照只报 amap：wiki 必须是 NaN（不是「0 次调用」）
        when(bridge.status()).thenReturn(snapshot(row("amap", true, 2, 7, 1, 3, "open")));
        new McpGovernanceMetrics(prometheus, props(true, "amap", "wiki"), () -> bridge);

        String text = prometheus.scrape();
        assertThat(text).contains("# TYPE mcp_server_enabled gauge");
        assertThat(text).contains("mcp_server_enabled 1.0");
        assertThat(text).contains("mcp_server_calls{server=\"amap\"} 7.0");
        assertThat(text).contains("mcp_server_failures{server=\"amap\"} 1.0");
        assertThat(text).contains("mcp_server_rejected{server=\"amap\"} 3.0");
        assertThat(text).contains("mcp_server_circuit{server=\"amap\",state=\"open\"} 1.0");
        assertThat(text).contains("mcp_server_circuit{server=\"amap\",state=\"closed\"} 0.0");
        // 关键：不可观测是 NaN，Prometheus 里既不是 0、也不会命中 ==1 / >0 的告警
        assertThat(text).contains("mcp_server_calls{server=\"wiki\"} NaN");
        // 配额未配置：两条窗口序列同样是 NaN，不能被读成「本分钟 0 次调用」
        assertThat(text).contains("# TYPE mcp_server_calls_this_minute gauge");
        assertThat(text).contains("mcp_server_calls_this_minute{server=\"amap\"} NaN");
        assertThat(text).contains("mcp_server_quota{server=\"amap\"} NaN");
    }
}