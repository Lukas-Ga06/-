package com.travel.agent.observability;

import com.travel.agent.config.McpProperties;
import com.travel.agent.mcp.McpGovernor;
import com.travel.agent.mcp.McpToolBridge;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * MCP 逐 server 治理指标：{@code mcp.server.*}（Gauge，抓取时现读 {@link McpToolBridge} 的实时快照）。
 *
 * <p><b>为什么是 Gauge 而不是 Counter：</b>调用/失败/拒绝的唯一权威在 {@link McpGovernor}（一把锁同时维护
 * 计数与熔断状态机）。若在埋点处并行累加一个 Micrometer Counter，就有两份会漂移的真相，而告警与
 * {@code /api/system/status} 必须读到同一个数。Gauge 每次抓取现读闸门快照：单调递增的语义不变
 * （Prometheus 照常 {@code rate()}/{@code increase()}），只是类型标成 gauge。
 *
 * <p><b>NaN 纪律（本类的核心契约）：</b>MCP 未启用、桥接还没启动（{@code McpToolBridge} 是异步
 * {@code SmartLifecycle}，启动瞬间 {@code status()} 里没有任何 server）、server 不在当前快照里时，
 * 逐 server 的 Gauge 报 <b>NaN</b>——绝不用 0 冒充「没调用/没失败」。NaN 不会命中 {@code > 0}/{@code == 1}
 * 告警，曲线是断点，能区分「查不到」与「真的是 0」。熔断三态在不可观测时<b>三条序列同时 NaN</b>，
 * 不能被读成 closed（否则「读不到状态」会被当成健康）。唯一例外是 {@link TravelMetrics#MCP_ENABLED}：
 * 开关在配置期已知，0 就是真实答案。
 *
 * <p><b>为什么 tag 用配置而不是运行时发现：</b>server 名来自 {@code travel.mcp.servers[].name}，部署期固定；
 * 桥接是异步启动的，启动瞬间拿不到 server 列表，若等它发现就会漏注册一批序列（并让「第一次抓取前没有指标」
 * 变成默认状态）。基数因此受配置约束（每个 server 固定 5 条序列 + 熔断 3 条），不随流量增长。
 *
 * <p><b>Bean 依赖方向：</b>不注入 {@link McpToolBridge} 本体，只注入 {@link ObjectProvider}，
 * 在 supplier 里运行时再取——避免指标组件把桥接bean 提前拉起来，也不制造与它的构造期循环。
 * 桥接不存在（未装配/已关闭）时 supplier 返回 null，采样为 NaN。
 *
 * <p>默认返回 NaN 而不是抛异常：不依赖 Micrometer 对 Gauge 异常的兜底行为，也避免一个坏序列
 * 打断整次 {@code /actuator/prometheus} 抓取（那会把所有指标一起弄丢）。
 */
@Component
@Slf4j
public class McpGovernanceMetrics {

    /** 熔断状态 tag 取值：直接取自 {@link McpGovernor.CircuitState#tag()}，改枚举不会让指标/告警静默错位 */
    static final List<String> CIRCUIT_STATES =
            Arrays.stream(McpGovernor.CircuitState.values()).map(McpGovernor.CircuitState::tag).toList();

    /** 配置缺省 server 名（与 {@code McpToolBridge} 的显示名口径一致，否则 tag 与快照永远对不上） */
    static final String UNNAMED_SERVER = "unnamed";

    static final String TAG_SERVER = "server";
    static final String TAG_STATE = "state";

    /** 未知/不可观测：上报 NaN，绝不返回 0 */
    private static final double UNKNOWN = Double.NaN;

    private final McpProperties properties;
    /** 运行时取桥接：返回 null 表示桥接不存在（未装配），采样按 NaN 处理 */
    private final Supplier<McpToolBridge> bridges;

    @Autowired
    public McpGovernanceMetrics(MeterRegistry meterRegistry, McpProperties properties,
                                ObjectProvider<McpToolBridge> bridgeProvider) {
        this(meterRegistry, properties, bridgeProvider::getIfAvailable);
    }

    /** 包内可见的构造器：单测直接给一个桥接供给函数（不需要 Spring 容器） */
    McpGovernanceMetrics(MeterRegistry meterRegistry, McpProperties properties,
                         Supplier<McpToolBridge> bridges) {
        this.properties = properties;
        this.bridges = bridges == null ? () -> null : bridges;

        Gauge.builder(TravelMetrics.MCP_ENABLED, this::enabledValue)
                .description("MCP 总开关（travel.mcp.enabled）：1=启用，0=未启用")
                .register(meterRegistry);

        Set<String> servers = configuredServers();
        for (String server : servers) {
            registerServerGauges(meterRegistry, server);
        }
        log.info("已注册 MCP 治理指标: {}（无 tag）、{}/{}/{}/{}/{}{{server=<配置名>}}、"
                        + "{}{{server=<配置名>,state=closed|open|half_open}}、"
                        + "{}/{}{{server=<配置名>}}（server 取自 travel.mcp.servers，共 {} 个；"
                        + "桥接未启动、server 不在快照或配额未配置时采样为 NaN）",
                TravelMetrics.MCP_ENABLED,
                TravelMetrics.MCP_SERVER_CONNECTED, TravelMetrics.MCP_SERVER_TOOLS, TravelMetrics.MCP_SERVER_CALLS,
                TravelMetrics.MCP_SERVER_FAILURES, TravelMetrics.MCP_SERVER_REJECTED,
                TravelMetrics.MCP_SERVER_CIRCUIT,
                TravelMetrics.MCP_SERVER_CALLS_THIS_MINUTE, TravelMetrics.MCP_SERVER_QUOTA,
                servers.size());
    }

    /** 总开关：配置期已知的事实，可以如实报 0（不是「观测不到」） */
    private double enabledValue() {
        try {
            return properties != null && properties.isEnabled() ? 1d : 0d;
        } catch (RuntimeException e) {
            log.debug("读取 MCP 开关失败: {}", e.getMessage());
            return UNKNOWN;
        }
    }

    private void registerServerGauges(MeterRegistry registry, String server) {
        register(registry, TravelMetrics.MCP_SERVER_CONNECTED, server,
                "MCP server 是否接入成功：1=已接入并注册工具，0=连接/握手失败（原因见 mcpLastError）",
                row -> row.connected() ? 1d : 0d);
        register(registry, TravelMetrics.MCP_SERVER_TOOLS, server,
                "该 MCP server 注册进 ToolRegistry 的工具数（按 allow-tools 过滤后）",
                row -> row.toolCount());
        register(registry, TravelMetrics.MCP_SERVER_CALLS, server,
                "该 MCP server 被放行并执行的累计调用数（含失败尝试；判读速率用 rate()）",
                row -> row.calls());
        register(registry, TravelMetrics.MCP_SERVER_FAILURES, server,
                "该 MCP server 的累计调用失败数（只统计放行后的执行失败，不含被闸门拒绝的调用）",
                row -> row.failures());
        register(registry, TravelMetrics.MCP_SERVER_REJECTED, server,
                "该 MCP server 被治理闸门拒绝的累计调用数（白名单/配额/熔断）",
                row -> row.rejected());
        for (String state : CIRCUIT_STATES) {
            Gauge.builder(TravelMetrics.MCP_SERVER_CIRCUIT, () -> circuitValue(server, state))
                    .description("该 MCP server 的熔断状态：命中 state 为 1，其余为 0；不可观测时三态同时 NaN")
                    .tag(TAG_SERVER, server)
                    .tag(TAG_STATE, state)
                    .register(registry);
        }
        registerNullable(registry, TravelMetrics.MCP_SERVER_CALLS_THIS_MINUTE, server,
                "该 MCP server 在当前 60 秒窗口内的已用调用数（配额未配置时为 NaN，不是 0 次）",
                McpToolBridge.McpServerStatus::callsThisMinute);
        registerNullable(registry, TravelMetrics.MCP_SERVER_QUOTA, server,
                "该 MCP server 配置的每分钟调用上限（配额未配置时为 NaN）",
                McpToolBridge.McpServerStatus::maxCallsPerMinute);
    }

    /**
     * 注册一条「可能不可知」的整数序列：值为 null 表示上游明确表示「没有这个事实」
     * （未配置配额），此时采样为 NaN——把 null 当 0 会造出「本分钟没调用」的假事实。
     */
    private void registerNullable(MeterRegistry registry, String name, String server, String description,
                                  Function<McpToolBridge.McpServerStatus, Integer> picker) {
        Gauge.builder(name, () -> nullableValue(server, picker))
                .description(description)
                .tag(TAG_SERVER, server)
                .register(registry);
    }

    private void register(MeterRegistry registry, String name, String server, String description,
                          ToDoubleFunction<McpToolBridge.McpServerStatus> picker) {
        Gauge.builder(name, () -> serverValue(server, picker))
                .description(description)
                .tag(TAG_SERVER, server)
                .register(registry);
    }

    /** 单条序列的采样：快照里找不到该 server 就是 NaN，不回落成 0 */
    private double serverValue(String server, ToDoubleFunction<McpToolBridge.McpServerStatus> picker) {
        McpToolBridge.McpServerStatus row = row(server);
        if (row == null) {
            return UNKNOWN;
        }
        try {
            return picker.applyAsDouble(row);
        } catch (RuntimeException e) {
            log.debug("读取 MCP 治理计数失败: server={} err={}", server, e.getMessage());
            return UNKNOWN;
        }
    }

    /** 三态熔断：命中 1、其余 0；状态字符串缺失时整组 NaN（不能把「读不到」读成 closed） */
    private double circuitValue(String server, String state) {
        McpToolBridge.McpServerStatus row = row(server);
        if (row == null || row.circuit() == null) {
            return UNKNOWN;
        }
        return state.equals(row.circuit()) ? 1d : 0d;
    }

    /**
     * 可空整数的采样：null = 上游明确「没有这个事实」（配额未配置），报 NaN；
     * 绝不回落成 0，否则「没设配额」会被读成「本分钟 0 次调用」。
     */
    private double nullableValue(String server, Function<McpToolBridge.McpServerStatus, Integer> picker) {
        McpToolBridge.McpServerStatus row = row(server);
        if (row == null) {
            return UNKNOWN;
        }
        try {
            Integer value = picker.apply(row);
            return value == null ? UNKNOWN : value.doubleValue();
        } catch (RuntimeException e) {
            log.debug("读取 MCP 配额计数失败: server={} err={}", server, e.getMessage());
            return UNKNOWN;
        }
    }

    /**
     * 取该 server 的当前状态行：桥接不存在、未启用、未启动（{@code running=false}，例如启动前/停止后）
     * 或该 server 不在快照里，一律返回 null（调用方据此报 NaN）。
     *
     * <p>源码上每次采样各查一次快照：没有 IO，只是遍历内存里的 server 列表（数量受配置约束）。
     */
    private McpToolBridge.McpServerStatus row(String server) {
        McpToolBridge bridge = bridges.get();
        if (bridge == null) {
            return null;
        }
        McpToolBridge.McpStatusSnapshot snapshot;
        try {
            snapshot = bridge.status();
        } catch (RuntimeException e) {
            log.debug("读取 MCP 状态快照失败: {}", e.getMessage());
            return null;
        }
        if (snapshot == null || !snapshot.enabled() || !snapshot.running()) {
            return null;
        }
        for (McpToolBridge.McpServerStatus row : snapshot.servers()) {
            if (row != null && server.equals(row.name())) {
                return row;
            }
        }
        return null;
    }

    /**
     * 要注册的 server 名（去重、保序）：用配置名而不是运行时发现，桥接异步启动也能在启动瞬间注册齐。
     * 空名按 {@value #UNNAMED_SERVER} 处理，与桥接快照的显示名保持一致。
     */
    private Set<String> configuredServers() {
        Set<String> names = new LinkedHashSet<>();
        List<McpProperties.Server> servers = properties == null ? null : properties.getServers();
        if (servers == null) {
            return names;
        }
        for (McpProperties.Server server : servers) {
            if (server != null) {
                names.add(displayName(server));
            }
        }
        return names;
    }

    private static String displayName(McpProperties.Server server) {
        String name = server.getName();
        return name == null || name.isBlank() ? UNNAMED_SERVER : name;
    }
}