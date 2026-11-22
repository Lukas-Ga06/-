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
            register(tool);
        }
    }

    /**
     * 注册工具（构造期注入与启动期 MCP 桥接共用）：写操作一律拒绝——
     * 会改变数据的操作必须实现为 AgentPlan 操作项，经确认后由 ExecutorAgent.commit 落地，
     * 不能作为工具被直接执行（否则绕过确认与撤销历史）。
     */
    public synchronized boolean register(AgentTool tool) {
        if (tools.containsKey(tool.getName())) {
            log.warn("工具 {} 已注册，跳过重复注册", tool.getName());
            return false;
        }
        if (!tool.readOnly()) {
            log.error("拒绝注册写操作工具 {}：会改变数据的操作必须实现为 AgentPlan 操作项，"
                    + "经确认后由 ExecutorAgent.commit 落地", tool.getName());
            return false;
        }
        tools.put(tool.getName(), tool);
        log.info("注册 AgentTool: {} [{}]", tool.getName(), tool.getRiskLevel());
        return true;
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public Map<String, AgentTool> all() {
        return tools;
    }
}
