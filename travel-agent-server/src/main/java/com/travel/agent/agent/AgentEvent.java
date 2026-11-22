package com.travel.agent.agent;

/** SSE 事件（event 名 + data），对应 SSE 流式协议 */
public record AgentEvent(String event, Object data) {
}
