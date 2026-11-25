package com.travel.agent.mcp;

import com.travel.agent.exception.BusinessException;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * MCP 工具适配器：把远端 MCP 工具包装成项目统一的只读 AgentTool。
 *
 * <p>治理（第二阶段：逐 server 治理）：每次调用先过 {@link McpGovernor}——白名单、熔断、配额；
 * 成功/失败/被拒各记一行 {@code [MCP-Audit]} 结构化日志（参数由 SafetyInterceptor 另行审计）。
 * 被治理闸门拒绝时不发起外部请求，按 403/503 抛 {@link BusinessException}：
 * 调用方（研究循环）会把它记成失败观察让模型自纠，而不是静默伪造结果。
 */
@Slf4j
public class McpAgentTool implements AgentTool {

    /** 注册进 ToolRegistry 的名字（mcp_{server}_{tool}，避免与内置工具冲突） */
    private final String name;
    /** MCP server 侧的原始工具名 */
    private final String remoteName;
    private final String description;
    private final boolean readOnly;
    private final McpConnection connection;
    /** 所属 server 的显示名（审计日志用，未 sanitize） */
    private final String serverName;
    private final McpGovernor governor;

    public McpAgentTool(String name, String remoteName, String description, boolean readOnly,
                        McpConnection connection, String serverName, McpGovernor governor) {
        this.name = name;
        this.remoteName = remoteName;
        this.description = description;
        this.readOnly = readOnly;
        this.connection = connection;
        this.serverName = serverName;
        this.governor = governor;
    }

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
        McpGovernor.Decision decision = governor.beforeCall(remoteName);
        if (!decision.allowed()) {
            log.warn("[MCP-Audit] server={} tool={} outcome=rejected reason={}",
                    serverName, remoteName, sanitize(decision.reason()));
            throw new BusinessException(decision.statusCode(),
                    "外部工具 " + name + " 暂时不可用：" + decision.reason());
        }
        long startedAt = System.currentTimeMillis();
        try {
            Object result = connection.callTool(remoteName, args);
            governor.recordSuccess();
            log.info("[MCP-Audit] server={} tool={} outcome=success durationMs={} {}",
                    serverName, remoteName, System.currentTimeMillis() - startedAt, governor.auditTag());
            return result;
        } catch (BusinessException e) {
            governor.recordFailure(e.getMessage());
            log.warn("[MCP-Audit] server={} tool={} outcome=failure durationMs={} reason={} {}",
                    serverName, remoteName, System.currentTimeMillis() - startedAt,
                    sanitize(e.getMessage()), governor.auditTag());
            throw e;
        } catch (Exception e) {
            governor.recordFailure(e.getMessage());
            log.error("[MCP-Audit] server={} tool={} outcome=failure durationMs={} reason={} {}",
                    serverName, remoteName, System.currentTimeMillis() - startedAt,
                    sanitize(e.getMessage()), governor.auditTag());
            // 走 BusinessException：AgentEngine 会把原因原样告诉用户，而不是笼统的"系统异常"
            throw new BusinessException(502, "外部工具 " + name + " 暂时不可用：" + e.getMessage());
        }
    }

    /** 审计日志只留一行：异常消息里的换行会把一条日志拆成多条（也挡住日志伪造） */
    private static String sanitize(String message) {
        return message == null ? "" : message.replaceAll("[\\r\\n]+", " ");
    }
}