package com.travel.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 所有工具统一注册 */
@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> allTools) {
        for (AgentTool tool : allTools) {
            if (!tool.readOnly()) {
                // 写操作必须走 AgentPlan → 确认 → commit，不能作为工具被直接执行（否则绕过确认与撤销历史）
                log.error("拒绝注册写操作工具 {}：会改变数据的操作必须实现为 AgentPlan 操作项，"
                        + "经确认后由 ExecutorAgent.commit 落地", tool.getName());
                continue;
            }
            tools.put(tool.getName(), tool);
            log.info("注册 AgentTool: {} [{}]", tool.getName(), tool.getRiskLevel());
        }
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public Map<String, AgentTool> all() {
        return tools;
    }
}
