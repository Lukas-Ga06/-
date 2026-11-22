package com.travel.agent.mcp;

import com.travel.agent.exception.BusinessException;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** MCP 工具适配器：把远端 MCP 工具包装成项目统一的只读 AgentTool */
@Slf4j
@RequiredArgsConstructor
public class McpAgentTool implements AgentTool {

    /** 注册进 ToolRegistry 的名字（mcp_{server}_{tool}，避免与内置工具冲突） */
    private final String name;
    /** MCP server 侧的原始工具名 */
    private final String remoteName;
    private final String description;
    private final boolean readOnly;
    private final McpConnection connection;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        try {
            return connection.callTool(remoteName, args);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("MCP 工具调用失败: {}", name, e);
            // 走 BusinessException：AgentEngine 会把原因原样告诉用户，而不是笼统的"系统异常"
            throw new BusinessException(502, "外部工具 " + name + " 暂时不可用：" + e.getMessage());
        }
    }
}
