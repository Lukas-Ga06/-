package com.travel.agent.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.travel.agent.dto.ChatDtos;
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

    /** 多模态上下文：用户当前选中的元素，解决「这个换成类似的」的指代 */
    private ChatDtos.SelectedElement selectedElement;
    private ChatDtos.MapLocation mapCenter;

    private AgentState state = AgentState.RECEIVE;
    private Map<String, Object> variables = new HashMap<>();

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
