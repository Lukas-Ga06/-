package com.travel.agent.agent;

/** Agent 状态机：RECEIVE → UNDERSTAND → PLAN → EXECUTE → REFLECT → PRESENT → CONFIRM → COMMIT */
public enum AgentState {
    RECEIVE,
    UNDERSTAND,
    PLAN,
    EXECUTE,
    REFLECT,
    PRESENT,
    CONFIRM,
    COMMIT,
    REPLAN,
    ERROR
}
