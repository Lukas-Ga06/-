package com.travel.agent.agent;

/**
 * SSE 事件（event 名 + data），对应 SSE 流式协议。
 *
 * <p>P0 起增加 {@code seq}：同一次 run 内从 1 开始单调递增，SSE 帧 id 形如
 * {@code <runId>:<seq>}。客户端断线重连时凭 Last-Event-ID 只补发 seq 之后的事件，
 * 不必重新规划（重复规划会再次消耗 LLM 配额）。
 *
 * <p>两参构造器保留给不参与持久化的旧调用方：{@code seq = 0}，帧上不带 id，
 * 行为与升级前一致。
 */
public record AgentEvent(String event, Object data, long seq) {

    public AgentEvent(String event, Object data) {
        this(event, data, 0L);
    }
}
