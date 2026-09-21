package com.travel.agent.tool;

import java.util.Map;

/** Agent 工具统一接口：所有工具经 ToolRegistry 注册、经 SafetyInterceptor 调用 */
public interface AgentTool {

    String getName();

    String getDescription();

    RiskLevel getRiskLevel();

    Object execute(Map<String, Object> args);
}
