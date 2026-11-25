package com.travel.agent.agent;

import com.travel.agent.llm.LlmUsage;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次 Agent 调用的「花费预算」（第二阶段：统一花费口径）。
 *
 * <p>背景：{@link AgentBudget} 只约束<b>时间</b>。时间是有界的，花费不是——一个不断
 * 「换参数再试一次」的研究循环、一次结构化规划失败后的带反馈重试、一个工具连发，
 * 都能在远不到 30s 的时间里烧掉数倍于正常请求的 token 与调用次数；而时间预算到点时
 * 已有的调用<b>已经花掉了钱</b>。这里补的是另一个维度：<b>调用次数、token、重试与估算费用</b>，
 * 与 deadline 叠加生效，任一到顶都按 ADR-002 的既有语义降级（如实告知，不伪造结果）。
 *
 * <p>三条口径纪律：
 * <ul>
 *   <li><b>只记真实用量</b>：token 来自供应商响应的 {@code usage}（{@link LlmUsage}），
 *       没有就记 0；绝不用「字符数 ÷ 4」之类估算值参与判定。</li>
 *   <li><b>上限是软上限</b>：调用次数/工具次数/重试在发起<b>前</b>判定，token 与费用只能
 *       在响应<b>后</b>才知道。因此一次在途调用可能刚好越过上限——越界量被「一次调用」封顶，
 *       下一次调用一定会被拒绝。这不是缺陷，是同步调用下唯一诚实的选择。</li>
 *   <li><b>关闭即零影响</b>：{@link #unlimited()} 恒放行且不累加计数，未设置花费预算的入口
 *       （渠道、单测直接构造上下文）行为与升级前逐字一致。</li>
 * </ul>
 *
 * <p>线程安全：计数器全部是原子类型，可随 {@link AgentContext} 交给 Coordinator 的并行任务；
 * 上限与单价是不可变字段，构造后只读。
 */
public final class AgentSpend {

    /** 不限量哨兵：任何计数/金额上限取该值表示「这一项不设限」 */
    public static final int UNLIMITED = -1;

    /**
     * 触发上限的花费项，声明顺序即 {@link #exhausted()} 的检查顺序（同样是报告顺序：
     * 先报最先被拒的那一类，避免「token 也满了」把真正卡住循环的原因盖掉）。
     */
    public enum Limit {
        /** LLM 调用次数（含失败的尝试） */
        LLM_CALLS("llm_calls"),
        /** 工具调用次数（含失败与重复被跳过的调用之外的真实执行） */
        TOOL_CALLS("tool_calls"),
        /** 重试次数（结构化规划带反馈重试、审核重规划） */
        RETRIES("retries"),
        /** token 总量（输入 + 输出） */
        TOKENS("tokens"),
        /** 估算费用（美元，按配置单价折算） */
        COST("cost");

        private final String tag;

        Limit(String tag) {
            this.tag = tag;
        }

        /** 指标 tag 取值（低基数枚举，禁止把 runId/用户放进 tag） */
        public String tag() {
            return tag;
        }
    }

    /**
     * 不可变配置。
     *
     * <p>各项上限 &lt; 0 表示不设限；单价为 0 时费用恒为 0（不估算）——
     * 也就是说「未配置价目表」时 {@link Limit#COST} 永远不会触发，这是刻意的：
     * 用错的价目表判定预算，比不判定更危险。
     *
     * @param enabled 花费预算开关；false 时全部放行且不计数
     */
    public record Config(boolean enabled, int maxLlmCalls, int maxToolCalls, int maxRetries,
                         long maxTokens, double maxCostUsd,
                         double promptPricePer1kUsd, double completionPricePer1kUsd) {

        /** 不限量的配置（同时不计数） */
        public static Config unlimited() {
            return new Config(false, UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, 0d, 0d);
        }
    }

    /**
     * 花费快照（日志、指标与单测断言用）。
     *
     * @param exhausted 第一个触顶的花费项；没有任何一项触顶时为 {@code null}
     */
    public record Snapshot(int llmCalls, int toolCalls, int retries, long promptTokens,
                           long completionTokens, double costUsd, Limit exhausted) {
        /** 总 token（输入 + 输出） */
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /** 未启用花费预算的单例：全部放行、不计数 */
    private static final AgentSpend UNLIMITED_SPEND = new AgentSpend(Config.unlimited());

    private final Config config;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    private AgentSpend(Config config) {
        this.config = config;
    }

    /** 不设限（与 {@link AgentBudget#unlimited()} 配套使用） */
    public static AgentSpend unlimited() {
        return UNLIMITED_SPEND;
    }

    /** 按配置创建一份可累加的预算 */
    public static AgentSpend of(Config config) {
        return config == null || !config.enabled() ? UNLIMITED_SPEND : new AgentSpend(config);
    }

    /** 花费预算是否生效；false 时所有 allows* 恒 true、所有 record* 是空操作 */
    public boolean isEnabled() {
        return config.enabled();
    }

    // ==================== LLM 调用 ====================

    /**
     * 是否还能再发起一次 LLM 调用：调用次数、token、费用三者任一触顶就拒绝。
     *
     * <p>调用方应在阶段边界判它（那里能生成降级事件与 partial 结果）；
     * {@link AgentBudget#chat} 里还有一道同样的兜底，防止新调用点漏判。
     */
    public boolean allowsLlmCall() {
        return !config.enabled()
                || (below(config.maxLlmCalls(), llmCalls.get())
                && below(config.maxTokens(), totalTokens())
                && below(config.maxCostUsd(), costUsd()));
    }

    /** 记一次 LLM 调用尝试（在发起前计数：超时/失败的尝试同样占用供应商配额，必须计入） */
    public void recordLlmCall() {
        if (config.enabled()) {
            llmCalls.incrementAndGet();
        }
    }

    /** 记一次调用的真实用量；{@link LlmUsage#NONE}（供应商未返回 usage）不改变任何计数 */
    public void recordUsage(LlmUsage usage) {
        if (!config.enabled() || usage == null || !usage.isPresent()) {
            return;
        }
        promptTokens.addAndGet(Math.max(0L, usage.promptTokens()));
        completionTokens.addAndGet(Math.max(0L, usage.completionTokens()));
    }

    // ==================== 工具调用 ====================

    /** 是否还能再执行一次工具：只看工具次数（工具不消耗 token，token 上限不拦工具） */
    public boolean allowsToolCall() {
        return !config.enabled() || below(config.maxToolCalls(), toolCalls.get());
    }

    /** 记一次真实执行的工具调用（被白名单/去重跳过的调用不计：它们没有产生外部副作用） */
    public void recordToolCall() {
        if (config.enabled()) {
            toolCalls.incrementAndGet();
        }
    }

    // ==================== 重试 ====================

    /**
     * 是否还能再重试一次：重试是花费放大器（每次都带着更长的反馈提示词再问一遍），
     * 单独设限，避免「失败→重试→再失败→再重试」把单次请求放大成十几次调用。
     */
    public boolean allowsRetry() {
        return !config.enabled() || below(config.maxRetries(), retries.get());
    }

    /** 记一次重试（带反馈的第二次结构化规划、审核阶段的第 N 轮重规划） */
    public void recordRetry() {
        if (config.enabled()) {
            retries.incrementAndGet();
        }
    }

    // ==================== 用量与报告 ====================

    /** 总 token（输入 + 输出） */
    public long totalTokens() {
        return promptTokens.get() + completionTokens.get();
    }

    /**
     * 按配置单价折算的估算费用（美元）；单价为 0 或未启用时恒为 0。
     *
     * <p>与 {@code TravelMetrics#estimateCostUsd} 同一口径：不猜价、不估算 token。
     */
    public double costUsd() {
        if (!config.enabled()) {
            return 0d;
        }
        double promptPrice = Math.max(0d, config.promptPricePer1kUsd());
        double completionPrice = Math.max(0d, config.completionPricePer1kUsd());
        if (promptPrice <= 0 && completionPrice <= 0) {
            return 0d;
        }
        return promptTokens.get() / 1000d * promptPrice + completionTokens.get() / 1000d * completionPrice;
    }

    /** 第一个触顶的花费项；全部未触顶返回空 */
    public Optional<Limit> exhausted() {
        if (!config.enabled()) {
            return Optional.empty();
        }
        if (!below(config.maxLlmCalls(), llmCalls.get())) {
            return Optional.of(Limit.LLM_CALLS);
        }
        if (!below(config.maxToolCalls(), toolCalls.get())) {
            return Optional.of(Limit.TOOL_CALLS);
        }
        if (!below(config.maxRetries(), retries.get())) {
            return Optional.of(Limit.RETRIES);
        }
        if (!below(config.maxTokens(), totalTokens())) {
            return Optional.of(Limit.TOKENS);
        }
        if (!below(config.maxCostUsd(), costUsd())) {
            return Optional.of(Limit.COST);
        }
        return Optional.empty();
    }

    /**
     * 触顶原因的一句话说明（用户可见的降级文案用它）；没有触顶时返回空串。
     *
     * <p>只写「哪一项、用掉多少、上限多少」——不带内部数组/对象，也不带 runId。
     */
    public String describeExhaustion() {
        Optional<Limit> exhausted = exhausted();
        if (exhausted.isEmpty()) {
            return "";
        }
        return switch (exhausted.get()) {
            case LLM_CALLS -> "LLM 调用已用 " + llmCalls.get() + "/" + config.maxLlmCalls() + " 次";
            case TOOL_CALLS -> "工具调用已用 " + toolCalls.get() + "/" + config.maxToolCalls() + " 次";
            case RETRIES -> "重试已用 " + retries.get() + "/" + config.maxRetries() + " 次";
            case TOKENS -> "token 已用 " + totalTokens() + "/" + config.maxTokens();
            case COST -> "估算费用已用 " + money(costUsd()) + "/" + money(config.maxCostUsd());
        };
    }

    /**
     * 一行用量摘要（日志用）：{@code llm=3/20 tool=2/20 retry=0/5 tokens=8123/200000 cost=$0.0021/1.0000}。
     * 不设限的项显示为 ∞。未启用时返回固定文案——日志里必须能一眼看出「本轮没开花费预算」。
     */
    public String summary() {
        if (!config.enabled()) {
            return "花费预算未启用";
        }
        return "llm=" + llmCalls.get() + "/" + cap(config.maxLlmCalls())
                + " tool=" + toolCalls.get() + "/" + cap(config.maxToolCalls())
                + " retry=" + retries.get() + "/" + cap(config.maxRetries())
                + " tokens=" + totalTokens() + "/" + cap(config.maxTokens())
                + " cost=" + money(costUsd()) + "/" + costCap(config.maxCostUsd());
    }

    /** 当前快照（一次性读取，供日志与指标使用） */
    public Snapshot snapshot() {
        return new Snapshot(llmCalls.get(), toolCalls.get(), retries.get(),
                promptTokens.get(), completionTokens.get(), costUsd(), exhausted().orElse(null));
    }

    /** 「未触顶」判定：上限 <0 表示不限；否则用掉 number 必须严格小于上限 */
    private static boolean below(int cap, long used) {
        return cap < 0 || used < cap;
    }

    private static boolean below(double cap, double used) {
        return cap < 0 || used < cap;
    }

    private static String cap(int cap) {
        return cap < 0 ? "∞" : String.valueOf(cap);
    }

    private static String cap(long cap) {
        return cap < 0 ? "∞" : String.valueOf(cap);
    }

    private static String costCap(double cap) {
        return cap < 0 ? "∞" : money(cap);
    }

    /** 金额统一 4 位小数：模型调用单价很低，2 位小数会把它抹成 $0.00 */
    private static String money(double usd) {
        return String.format(Locale.ROOT, "$%.4f", Math.max(0d, usd));
    }
}