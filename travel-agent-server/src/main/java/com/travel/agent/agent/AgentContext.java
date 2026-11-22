package com.travel.agent.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.travel.agent.dto.ChatDtos;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.service.ConversationService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Agent 上下文：每次 Agent 调用的全部环境信息 */
@Slf4j
@Data
public class AgentContext {

    private Long userId;
    private String username;
    private Long tripId;
    private String sessionId;
    private String message;
    private Integer currentDay;

    /**
     * 本轮对应的 Agent 运行标识（V2.2 追踪）：run 由引擎在开始时创建，
     * 工具调用 / 阶段任务据此挂到同一运行下；追踪不可用时为空（行为不受影响）。
     */
    private String runId;

    /**
     * 当前正在执行的阶段任务标识（工具调用追踪用它关联触发任务）。
     *
     * <p>第十五批起用 ThreadLocal 承载：任务图允许 PREVIEW / REVIEW 这类互不依赖的任务
     * 在同一 run 内并行，共享一个字段会互相覆盖、把工具调用挂到别人的任务上。
     * 对外访问方法签名不变（{@link #getCurrentTaskId()} / {@link #setCurrentTaskId(String)}）。
     */
    @JsonIgnore
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private final transient ThreadLocal<String> taskIdHolder = new ThreadLocal<>();

    /**
     * 会话记忆：前端生成的会话标识（跨页面切换保持），为空表示单轮对话。
     * 历史消息由 AgentEngine 在 UNDERSTAND 之前载入，只喂给 LLM 做指代消解。
     */
    private String conversationId;

    /**
     * 幂等标识（P0-01）：客户端生成、重试复用，服务端按 (userId, requestId) 保证写入最多一次。
     * 为空表示本条不做幂等（旧客户端 / 内部调用）。
     */
    private String requestId;

    /**
     * 会话作用域（V21）：{@code assistant} / {@code planner} / {@code trip:<id>}。
     * 只用于记录「这段会话最近一次用在哪个页面」，从而在续聊时恢复行程绑定；
     * 为空表示未绑定（不算错误，老客户端就是这样）。
     */
    private String scope;

    /**
     * 本轮的会话轮次序号（V23）：用户消息与随后的助手回复共用它，两行因此在历史里始终相邻。
     *
     * <p>由 {@code AgentEngine#loadConversation} 在开口时分配（compare-and-set，
     * 撞唯一键就换号重试）。{@code ConversationService.NO_TURN} 表示未分组
     * （单轮对话 / 分配失败）：回复照旧落库，只是不带分组标记。
     */
    private long turnSeq = ConversationService.NO_TURN;

    /** 多模态上下文：用户当前选中的元素，解决「这个换成类似的」的指代 */
    private ChatDtos.SelectedElement selectedElement;
    private ChatDtos.MapLocation mapCenter;

    /**
     * 仅凭 URL 票据认证的只读会话（没有 Cookie / Authorization 头）。
     *
     * <p>票据会随 URL 进入日志与 Referer，泄漏代价低；因此这种会话允许查询与规划，
     * 但不允许执行记账/备忘/日程这类直接落库的写意图（见 AgentEngine）。
     */
    private boolean ticketOnly;

    /**
     * 客户端断开（SSE onTimeout/onError/半途 onCompletion）后的协作式取消标记。
     *
     * <p>由 {@code AgentController} 的 SSE 生命周期回调置位，{@code AgentEngine} 在阶段边界检查，
     * 命中即停止后续 LLM / 工具调用。这里<b>不用</b> {@link Thread#interrupt()}：
     * Agent 任务跑在共享的 {@code agentTaskExecutor} 上，中断标志会残留到池线程、
     * 破坏排在其后的任务；协作式标记只影响本轮执行。
     *
     * <p>已经发出的阻塞调用（例如正在 wait 的 LLM HTTP 请求）不会被强行打断，
     * 它仍会跑完，只是结果不再进入下一个阶段。
     */
    @JsonIgnore
    private volatile boolean cancelled;

    /**
     * 本轮端到端时间预算（P1）。
     *
     * <p>由 {@code AgentController} 在请求入口创建（同步 /chat 与 SSE 共用同一份语义），
     * 阶段开始前据此检查剩余预算并按 ADR-002 降级。未显式设置时是
     * {@link AgentBudget#unlimited()}——渠道入口、单测直接构造的上下文行为与升级前一致。
     */
    @JsonIgnore
    private AgentBudget budget = AgentBudget.unlimited();

    /**
     * 预算降级留痕：哪个阶段因预算耗尽降级、当时的剩余预算。
     *
     * <p>用写时复制列表：PREVIEW / REVIEW 这类并行阶段可能各自记录，读侧（收尾提示、
     * 追踪任务、单测断言）不应与写入竞争。同一阶段只保留第一条——第一条最能说明在哪一步耗尽。
     */
    @JsonIgnore
    @lombok.EqualsAndHashCode.Exclude
    private final transient List<BudgetDegradation> budgetDegradations = new CopyOnWriteArrayList<>();

    /** 一条预算降级记录：stage=阶段标识（见 {@link AgentBudget}），detail=降级说明，remainingMs=降级时剩余预算 */
    public record BudgetDegradation(String stage, String detail, long remainingMs) {
    }

    /**
     * 本轮预算：<b>永不返回 null</b>。显式设置成 null（渠道/旧调用方）等价于「不受预算约束」，
     * 这样各阶段的检查代码不需要到处判空，未设置预算的入口行为也与升级前一致。
     */
    public AgentBudget getBudget() {
        return budget == null ? AgentBudget.unlimited() : budget;
    }

    /**
     * 记录一次「预算耗尽 → 阶段降级」。
     *
     * <p>三件事必须同时发生，缺一不可（验收口径：降级可观测、不得静默）：
     * WARN 日志带 runId 与剩余预算（运维可查，且与「LLM 不可用」的降级日志区分开）、
     * {@code budget_degraded} 事件（SSE 客户端可见）、上下文里的留痕（收尾提示与追踪任务据此生成）。
     */
    public void recordBudgetDegradation(String stage, String detail) {
        if (budgetDegradations.stream().anyMatch(d -> d.stage().equals(stage))) {
            return;
        }
        long remaining = getBudget().remainingMs();
        budgetDegradations.add(new BudgetDegradation(stage, detail, remaining));
        log.warn("Agent 预算耗尽触发降级: runId={} stage={} remainingMs={} detail={}",
                runId, stage, remaining, detail);
        emit("budget_degraded", Map.of("stage", stage, "detail", detail, "remainingMs", remaining));
    }

    /** 本轮是否发生过预算降级（收尾提示 / 追踪任务 / 单测断言用） */
    public boolean hasBudgetDegradations() {
        return !budgetDegradations.isEmpty();
    }

    private AgentState state = AgentState.RECEIVE;
    private Map<String, Object> variables = new HashMap<>();

    /** 本会话最近的消息（时间正序），@JsonIgnore 避免随 SSE 事件序列化出去 */
    @JsonIgnore
    private transient List<ConversationMessage> history = List.of();

    @JsonIgnore
    private final transient List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Consumer<AgentEvent> listener) {
        listeners.add(listener);
    }

    /** 当前线程正在执行的任务 id（无则 null）；并行任务各自独立，互不覆盖 */
    public String getCurrentTaskId() {
        return taskIdHolder.get();
    }

    /** 设置当前线程的任务 id；传 null 表示退出该任务（清理 ThreadLocal，避免池线程串味） */
    public void setCurrentTaskId(String taskId) {
        if (taskId == null) {
            taskIdHolder.remove();
        } else {
            taskIdHolder.set(taskId);
        }
    }

    /** 请求取消本轮 Agent 执行：幂等，可被任意线程（Servlet 容器回调）调用。 */
    public void cancel() {
        this.cancelled = true;
    }

    /** 本轮执行是否已被取消；阶段边界据此短路，不再发起新的 LLM / 工具调用。 */
    public boolean isCancelled() {
        return cancelled;
    }

    public void emit(String event, Object data) {
        AgentEvent agentEvent = new AgentEvent(event, data);
        for (Consumer<AgentEvent> listener : listeners) {
            try {
                listener.accept(agentEvent);
            } catch (Exception ignored) {
                // 单个监听器异常不影响主流程
            }
        }
    }
}
