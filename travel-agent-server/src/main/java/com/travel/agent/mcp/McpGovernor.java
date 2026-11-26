package com.travel.agent.mcp;

import com.travel.agent.config.McpProperties;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 单个 MCP server 的治理闸门：白名单 / 熔断 / 每分钟配额 / 审计计数。
 *
 * <p>为什么需要它：花费预算（{@code AgentSpend}）只给出「本轮调了多少次工具」，
 * 回答不了「哪个 server 在拖后腿、是不是某个 server 已经坏了还在被反复调用」。
 * 一个挂掉的 MCP server 会持续消耗工具次数与时间预算，直到整轮降级——
 * 而模型看不到「这个 server 已经连续失败 5 次」，它只会换个参数再试一次。
 *
 * <p>四件事，各自独立、都可关闭（默认行为与接入治理前一致）：
 * <ul>
 *   <li><b>白名单</b>（{@code allow-tools}）：为空表示不限制；非空时白名单外的工具
 *       既不被注册（模型看不到），也不允许调用（防御性二次校验）。</li>
 *   <li><b>熔断</b>（{@code failure-threshold} / {@code cooldown-ms}）：连续失败达到阈值即打开，
 *       冷却期内直接拒绝（快速失败，不再白等一次超时）；冷却结束放行一次半开探测，
 *       成功即闭合，失败立即重新打开。</li>
 *   <li><b>配额</b>（{@code max-calls-per-minute}）：跨 run 的 server 级滑动窗口，
 *       用来压住高德 {@code 10021} 这类上游 QPS 限流——配额应该花在真实请求上，
 *       而不是让并发调用把失败次数烧满。</li>
 *   <li><b>审计</b>：每次调用（成功/失败/被拒）由 {@link McpAgentTool} 记一行结构化日志，
 *       这里只维护低基数计数（总数/失败/拒绝）供 {@code /api/system/status} 展示。</li>
 * </ul>
 *
 * <p>线程安全：所有状态都在 {@code synchronized} 方法里读写。MCP 调用本身是网络/子进程 IO，
 * 这把锁只保护计数与状态机，不覆盖 IO，不会成为吞吐瓶颈。
 */
public final class McpGovernor {

    /** 配额窗口：固定 60 秒滑动窗口（按分钟重置，不做加权平均——运维看得懂） */
    static final long WINDOW_MS = 60_000L;

    /** 熔断状态；对外用 {@link #tag()} 输出小写枚举，方便指标/前端消费 */
    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 调用前判定结果。
     *
     * @param allowed    true 表示放行并已计入配额；false 时 {@link #statusCode()} 与 {@link #reason()} 说明原因
     * @param statusCode 拒绝时对外暴露的 HTTP 语义码（403=白名单，503=熔断/配额）
     */
    public record Decision(boolean allowed, int statusCode, String reason) {

        static Decision allow() {
            return new Decision(true, 0, null);
        }

        static Decision deny(int statusCode, String reason) {
            return new Decision(false, statusCode, reason);
        }
    }

    /**
     * 只读快照（状态接口与单测用）。
     *
     * @param calls          放行并执行的调用总数（含失败）
     * @param failures       失败总数
     * @param rejected       被白名单/熔断/配额拒绝的调用数
     * @param circuit        当前熔断状态
     * @param callsThisMinute 当前窗口内的调用数（未配置配额时为 0）
     */
    public record Snapshot(int calls, int failures, int rejected, CircuitState circuit, int callsThisMinute) { }

    private final String server;
    private final Set<String> allowTools;
    private final int maxCallsPerMinute;
    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clock;

    private int calls;
    private int failures;
    private int rejected;
    private int consecutiveFailures;
    private CircuitState circuit = CircuitState.CLOSED;
    /** 熔断打开的截止时间（epoch ms）；仅 {@link CircuitState#OPEN} 时有意义 */
    private long openUntil;
    /** 当前配额窗口起点；窗口按需重置，不依赖定时任务 */
    private long windowStart;
    private int windowCalls;

    public McpGovernor(String server, McpProperties.Server config) {
        this(server, config, System::currentTimeMillis);
    }

    /** 可注入时钟的构造器（单测用；生产走 {@link System#currentTimeMillis()}） */
    McpGovernor(String server, McpProperties.Server config, LongSupplier clock) {
        this.server = server == null || server.isBlank() ? "unnamed" : server;
        Set<String> tools = new LinkedHashSet<>();
        if (config != null && config.getAllowTools() != null) {
            for (String tool : config.getAllowTools()) {
                if (tool != null && !tool.isBlank()) {
                    tools.add(tool.trim());
                }
            }
        }
        this.allowTools = Set.copyOf(tools);
        this.maxCallsPerMinute = config == null ? 0 : Math.max(0, config.getMaxCallsPerMinute());
        this.failureThreshold = config == null ? 0 : Math.max(0, config.getFailureThreshold());
        this.cooldownMs = config == null ? 60_000L : Math.max(0L, config.getCooldownMs());
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 配置的每分钟调用上限（0 = 未配置配额）。
     *
     * <p>为什么要把「上限」也暴露出去：{@link Snapshot#callsThisMinute()} 在<b>未配置配额</b>时恒为 0，
     * 与「本窗口确实一次都没调用」是同一个 0。调用方（状态接口/指标）必须能分辨这两种含义，
     * 否则「没设配额」会被读成「这个 server 很闲」。
     */
    public int maxCallsPerMinute() {
        return maxCallsPerMinute;
    }

    /** 白名单判定（注册期用它过滤工具；为空表示不限制） */
    public boolean permitsTool(String remoteTool) {
        return allowTools.isEmpty() || allowTools.contains(remoteTool);
    }

    /**
     * 调用前判定：白名单 → 配额 → 熔断（顺序有意义：被配额拒绝的调用不应该推进熔断状态）。
     * 放行时立刻计入调用数与窗口配额——「发起前计数」与花费预算同口径：
     * 超时/失败的尝试同样消耗上游配额，必须计入。
     */
    public synchronized Decision beforeCall(String remoteTool) {
        if (!permitsTool(remoteTool)) {
            rejected++;
            return Decision.deny(403, "工具不在 allow-tools 白名单内（server=" + server
                    + "，tool=" + remoteTool + "）");
        }
        long now = clock.getAsLong();
        if (maxCallsPerMinute > 0) {
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                windowCalls = 0;
            }
            if (windowCalls >= maxCallsPerMinute) {
                rejected++;
                return Decision.deny(503, "每分钟调用上限已用尽（server=" + server
                        + "，上限 " + maxCallsPerMinute + " 次/分钟）");
            }
        }
        if (circuit == CircuitState.OPEN) {
            if (now < openUntil) {
                rejected++;
                return Decision.deny(503, "熔断中（server=" + server + "，连续失败 "
                        + consecutiveFailures + " 次，剩余冷却 " + Math.max(0L, openUntil - now) + " ms）");
            }
            // 冷却结束：本次调用作为半开探测，其余并发调用继续拒绝
            circuit = CircuitState.HALF_OPEN;
        } else if (circuit == CircuitState.HALF_OPEN) {
            rejected++;
            return Decision.deny(503, "半开探测进行中（server=" + server + "），本次不重复放行");
        }
        calls++;
        if (maxCallsPerMinute > 0) {
            windowCalls++;
        }
        return Decision.allow();
    }

    /** 调用成功：清零连续失败；半开探测成功即闭合熔断 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        circuit = CircuitState.CLOSED;
    }

    /**
     * 调用失败：累计连续失败；达到阈值（或半开探测失败）打开熔断。
     * {@code reason} 只进日志，不进状态快照，避免把上游错误文本带进无鉴权的接口。
     */
    public synchronized void recordFailure(String reason) {
        failures++;
        consecutiveFailures++;
        if (failureThreshold <= 0) {
            return;
        }
        if (circuit == CircuitState.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            circuit = CircuitState.OPEN;
            openUntil = clock.getAsLong() + cooldownMs;
        }
    }

    /** 只读快照；调用方（桥接层/状态接口）可能来自任意线程 */
    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        boolean windowActive = maxCallsPerMinute > 0 && now - windowStart < WINDOW_MS;
        return new Snapshot(calls, failures, rejected, circuit, windowActive ? windowCalls : 0);
    }

    /** 审计日志用的一行摘要（不含参数/结果，参数由 SafetyInterceptor 另行审计） */
    public synchronized String auditTag() {
        Snapshot snap = snapshot();
        return "state=" + snap.circuit().tag() + " calls=" + snap.calls()
                + " failures=" + snap.failures() + " rejected=" + snap.rejected()
                + (maxCallsPerMinute > 0 ? " minuteCalls=" + snap.callsThisMinute() + "/" + maxCallsPerMinute : "");
    }
}