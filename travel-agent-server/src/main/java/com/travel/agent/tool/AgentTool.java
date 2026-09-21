package com.travel.agent.tool;

import java.util.Map;

/** Agent 工具统一接口：所有工具经 ToolRegistry 注册、经 SafetyInterceptor 调用 */
public interface AgentTool {

    String getName();

    String getDescription();

    RiskLevel getRiskLevel();

    /**
     * 是否只读。
     *
     * 架构约定：会改变数据的操作**不允许**做成 AgentTool，必须表达为 AgentPlan 的操作项，
     * 经「preview → Diff → 用户确认 → ExecutorAgent.commit」落地，并写操作历史（可撤销）。
     * 因此这里默认 true；若某个工具返回 false，ToolRegistry 会拒绝注册它。
     */
    default boolean readOnly() {
        return true;
    }

    Object execute(Map<String, Object> args);
}
