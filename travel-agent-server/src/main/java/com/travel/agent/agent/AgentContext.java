package com.travel.agent.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.travel.agent.dto.ChatDtos;
import com.travel.agent.entity.ConversationMessage;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Agent 上下文：每次 Agent 调用的全部环境信息 */
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

    /** 当前正在执行的阶段任务标识（工具调用追踪用它关联触发任务） */
    private String currentTaskId;

    /**
     * 会话记忆：前端生成的会话标识（跨页面切换保持），为空表示单轮对话。
     * 历史消息由 AgentEngine 在 UNDERSTAND 之前载入，只喂给 LLM 做指代消解。
     */
    private String conversationId;

    /** 多模态上下文：用户当前选中的元素，解决「这个换成类似的」的指代 */
    private ChatDtos.SelectedElement selectedElement;
    private ChatDtos.MapLocation mapCenter;

    /**
     * 仅凭 URL 票据认证的只读会话（没有 Cookie / Authorization 头）。
     *
     * <p>票据会随 URL 进入日志与 Referer，泄漏代价低；因此这种会话允许查询与规划，
     * 但不允许执行记账/备忘/日程/日报这类直接落库的写意图（见 AgentEngine）。
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
