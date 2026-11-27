package com.travel.agent.agent;

import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;

import java.util.List;

/**
 * 一次 Agent 调用的端到端预算：<b>时间 deadline（不可变）+ 花费上限（原子累加）</b>。
 *
 * <p>背景（P1）：慢依赖下单次 LLM 调用会在读超时（30s）被截断，但一次规划串行跑
 * 意图 → 研究 → 结构化规划 → 审核 → 润色五个阶段，端到端可达 128s；用户看到的是
 * 「一直在转圈」，同步接口也没有任何总超时。这里用一个一次性创建、只读的 deadline
 * 约束整条主链路：<b>阶段开始前</b>检查剩余预算，预算不够就按 ADR-002
 * 「可以什么都不做」降级，而不是继续把时间烧在必然超时的调用上。
 *
 * <p>第二阶段（花费预算，{@link AgentSpend}）：时间有界、花费无界——同样的 30s 里，
 * 研究循环换参数重试、结构化规划带反馈重试、工具连发都能把 token 与调用次数放大数倍，
 * 而 deadline 到点时这些钱已经花掉了。因此把「调用次数 / 工具次数 / 重试 / token / 估算费用」
 * 叠加在同一个对象上：任一到顶都走同一套降级语义（{@link AgentContext#recordBudgetDegradation}），
 * 用户看到的仍是「如实说明没有继续」，不是伪造结果。
 *
 * <p>时间部分不可变 + 只看单调时钟（{@link System#nanoTime()}），花费部分用原子计数器累加；
 * 对象本身线程安全，可以随 {@link AgentContext} 交给 Coordinator 的并行任务使用。
 *
 * <p>两种实例：
 * <ul>
 *   <li>{@link #start(long, int)} / {@link #start(long, int, AgentSpend)}：由 Controller 在请求入口创建
 *       （同步 /chat 与 SSE 共用）；</li>
 *   <li>{@link #unlimited()}：未显式设置预算的入口（Telegram/Webhook 等渠道、单测直接造 ctx）
 *       拿到的就是它——<b>所有检查恒放行</b>，行为与升级前逐字一致。</li>
 * </ul>
 *
 * <p>为什么还要封顶单次 LLM 读超时（{@link #chat}）：只在阶段边界检查，最后一个阶段仍可能
 * 用满全局 30s 读超时，把「30s 预算」拖成 60s。预算开启时把每次调用的读超时压到「剩余预算」，
 * 这样在途调用本身也不会越过 deadline，端到端才是可控的。
 */
public final class AgentBudget {

    // ==================== 阶段标识（与 AgentStageTimer 的指标 tag 区分：这里是预算口径） ====================

    public static final String STAGE_INTENT = "intent";
    public static final String STAGE_RESEARCH = "research";
    public static final String STAGE_PLAN = "plan";
    public static final String STAGE_REVIEW = "review";
    public static final String STAGE_NARRATION = "narration";
    public static final String STAGE_TOOL = "tool";

    // ==================== 各阶段「还值不值得开始」的最小剩余时间 ====================

    /**
     * 发起一次 LLM 调用的最小剩余时间：低于它就必然读超时，发出去只会多烧一次配额。
     * 取 100ms 而不是几秒，是为了小预算场景（单测 200ms 级）也能走到「调用中耗尽」的真实降级路径。
     */
    public static final long MIN_LLM_CALL_MS = 100L;

    /** 研究循环单步的最小剩余时间（每步一次 LLM 决策 + 至多一次只读工具调用） */
    public static final long MIN_RESEARCH_STEP_MS = 100L;

    /**
     * LLM 结构化规划的最小剩余时间。结构化路径通常 2 次 LLM 调用（首轮 + 一次带反馈重试），
     * 剩余不足 1s 时即使发出去也大概率拿不到可用输出，直接走规则规划更快、更稳。
     */
    public static final long MIN_STRUCTURED_PLAN_MS = 1000L;

    /**
     * 审核（路线/天气/预算硬约束）的最小剩余时间。
     * 审核要查路线与天气（整条链路最慢的外部依赖段），剩余不足 1s 就跳过并如实告知「未审核」。
     */
    public static final long MIN_REVIEW_MS = 1000L;

    /** 文案润色的最小剩余时间：润色是锦上添花，时间不够就用模板原文 */
    public static final long MIN_NARRATION_MS = 1000L;

    /** 外部工具调用的最小剩余时间：用户显式要求的只读查询，给足一个工具调用窗口 */
    public static final long MIN_TOOL_MS = 500L;

    /** 未启用预算的单例：deadline 不存在、花费不设限，所有检查恒放行 */
    private static final AgentBudget UNLIMITED = new AgentBudget(false, 0L, 0, 0L, AgentSpend.unlimited());

    private final boolean enabled;
    private final long totalMs;
    /** 配置里的单次 LLM 全局读超时上限（{@code travel.llm.read-timeout-ms}），预算开启时按剩余预算封顶 */
    private final int llmReadTimeoutMs;
    private final long startedNanos;
    /** 叠加在 deadline 上的花费预算（调用次数/工具次数/重试/token/费用） */
    private final AgentSpend spend;

    private AgentBudget(boolean enabled, long totalMs, int llmReadTimeoutMs, long startedNanos, AgentSpend spend) {
        this.enabled = enabled;
        this.totalMs = totalMs;
        this.llmReadTimeoutMs = llmReadTimeoutMs;
        this.startedNanos = startedNanos;
        this.spend = spend == null ? AgentSpend.unlimited() : spend;
    }

    /**
     * 开启一个预算窗口（在请求入口调用一次），只约束时间。
     *
     * @param totalMs         端到端总预算，≤0 视为「一创建就已耗尽」（用于确定性测试）
     * @param llmReadTimeoutMs 单次 LLM 调用的配置读超时，作为封顶上限
     */
    public static AgentBudget start(long totalMs, int llmReadTimeoutMs) {
        return start(totalMs, llmReadTimeoutMs, AgentSpend.unlimited());
    }

    /**
     * 开启一个预算窗口：时间 deadline + 花费上限同时生效。
     *
     * @param totalMs          端到端总预算（毫秒），≤0 视为「一创建就已耗尽」
     * @param llmReadTimeoutMs 单次 LLM 调用的配置读超时，作为封顶上限
     * @param spend            花费预算；{@code null} 等价于 {@link AgentSpend#unlimited()}
     */
    public static AgentBudget start(long totalMs, int llmReadTimeoutMs, AgentSpend spend) {
        return new AgentBudget(true, Math.max(totalMs, 0L), Math.max(llmReadTimeoutMs, 1), System.nanoTime(), spend);
    }

    /** 不受预算约束（关闭开关 / 渠道入口 / 单测直接构造上下文） */
    public static AgentBudget unlimited() {
        return UNLIMITED;
    }

    /** 预算开关是否生效；false 时一切检查放行、调用方式保持升级前行为 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 花费预算（永不返回 null）：没有显式配置时是 {@link AgentSpend#unlimited()} */
    public AgentSpend spend() {
        return spend;
    }

    /** 配置的总预算（毫秒；未启用时为 0） */
    public long totalMs() {
        return totalMs;
    }

    /** 从创建到现在的耗时（毫秒） */
    public long elapsedMs() {
        return enabled ? Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L) : 0L;
    }

    /** 剩余预算（毫秒，最小 0）；未启用时返回 {@link Long#MAX_VALUE}，让日志/事件里的数值恒为「不约束」 */
    public long remainingMs() {
        return enabled ? Math.max(0L, totalMs - elapsedMs()) : Long.MAX_VALUE;
    }

    /** 预算是否已经耗尽 */
    public boolean isExhausted() {
        return enabled && remainingMs() <= 0L;
    }

    /**
     * 剩余预算是否够启动一个至少需要 {@code minRemainingMs} 的阶段/调用。
     * 未启用时恒 true（关闭开关 = 行为与升级前一致）。
     */
    public boolean allows(long minRemainingMs) {
        return !enabled || remainingMs() >= Math.max(minRemainingMs, 1L);
    }

    /**
     * 是否允许发起 LLM 调用：既要有最小可用剩余时间，封顶后的读超时也不能小于它，
     * 否则这次调用注定读超时，等于白白多等一次；同时花费预算（调用次数/token/费用）
     * 不能已经触顶。
     */
    public boolean allowsLlmCall(long minCallMs) {
        // 花费预算与时间预算互相独立：时间不设限（unlimited）不代表花费不设限
        if (!spend.allowsLlmCall()) {
            return false;
        }
        if (!enabled) {
            return true;
        }
        return allows(minCallMs) && llmReadTimeoutMs() >= minCallMs;
    }

    /**
     * 本次 LLM 调用可用的读超时：预算开启时压到剩余预算，未启用时返回配置值。
     * 这是「端到端预算」与「单次调用读超时」之间的桥——只有压住它，最后一个阶段才不会越过 deadline。
     */
    public int llmReadTimeoutMs() {
        if (!enabled) {
            return llmReadTimeoutMs;
        }
        return (int) Math.max(1L, Math.min((long) llmReadTimeoutMs, remainingMs()));
    }

    /**
     * 在预算约束下调用 LLM（各 Agent 唯一的模型调用入口，保证超时与花费口径一致）。
     *
     * <p>预算关闭时走原来的两参调用，连「按超时值缓存 RestClient」的行为都不改变；
     * 预算开启时把读超时压到剩余预算——调用最多用到 deadline 就返回，不会把总耗时拖出预算。
     *
     * <p>花费口径在这里收口：调用前判花费上限（触顶就不发、返回 null 让调用方走既有降级），
     * 调用后把供应商返回的真实 usage 记进 {@link AgentSpend}。放在这一个方法里，
     * 是为了让「新增的 Agent 阶段」不可能忘记记账——只要它用 budget.chat，就自动计费。
     */
    public String chat(LlmClient client, List<LlmMessage> messages, double temperature) {
        return chat(client, messages, temperature, enabled ? llmReadTimeoutMs() : 0);
    }

    /**
     * 带调用方自有读超时的版本（如文案润色的 8s 上限）：取「调用方要求」与「剩余预算」的较小值，
     * 既不让辅助能力拖满全局 30s，也不让它越过 deadline。
     *
     * @param preferredReadTimeoutMs 调用方希望的单次读超时；≤0 表示沿用配置的全局读超时
     */
    public String chat(LlmClient client, List<LlmMessage> messages, double temperature, int preferredReadTimeoutMs) {
        if (!spend.allowsLlmCall()) {
            // 花费触顶：这次调用根本不发出去。调用方本应在阶段边界判过（并留下降级说明），
            // 这里是兜底——宁可少发一次，也不让花费越过用户可见的上限。
            return null;
        }
        // 读走上一次可能残留的用量，确保下面记的是「这一次」的 usage（记账宁少不重）
        client.takeLastUsage();
        String content;
        if (!enabled) {
            // 未启用时间预算时保持「调用方要什么超时就用什么超时」的原行为：
            // 润色这类辅助能力本来就靠自己的 8s 上限活着，不能因为没开预算就退化成全局 30s。
            content = preferredReadTimeoutMs > 0
                    ? client.chat(messages, temperature, preferredReadTimeoutMs)
                    : client.chat(messages, temperature);
        } else {
            content = client.chat(messages, temperature, effectiveReadTimeoutMs(preferredReadTimeoutMs));
        }
        // 超时/失败的尝试同样占用供应商配额，先计次数；usage 只在供应商真的返回时才有
        spend.recordLlmCall();
        spend.recordUsage(client.takeLastUsage());
        return content;
    }

    /** 单次调用实际可用的读超时：调用方要求值与剩余预算取小，至少 1ms */
    private int effectiveReadTimeoutMs(int preferredReadTimeoutMs) {
        int preferred = preferredReadTimeoutMs > 0 ? preferredReadTimeoutMs : Math.max(llmReadTimeoutMs, 1);
        if (!enabled) {
            return preferred;
        }
        return (int) Math.max(1L, Math.min((long) preferred, remainingMs()));
    }
}