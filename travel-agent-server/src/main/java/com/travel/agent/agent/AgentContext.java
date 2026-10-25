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
     * 会话记忆：前端生成的会话标识（跨页面切换保持），为空表示单轮对话。
     * 历史消息由 AgentEngine 在 UNDERSTAND 之前载入，只喂给 LLM 做指代消解。
     */
    private String conversationId;

    /** 多模态上下文：用户当前选中的元素，解决「这个换成类似的」的指代 */
    private ChatDtos.SelectedElement selectedElement;
    private ChatDtos.MapLocation mapCenter;

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
