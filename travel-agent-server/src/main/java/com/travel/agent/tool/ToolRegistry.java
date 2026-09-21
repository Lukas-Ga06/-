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
