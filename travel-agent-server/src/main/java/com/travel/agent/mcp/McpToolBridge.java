package com.travel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.config.McpProperties;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 桥接：启动时逐个接入 travel.mcp.servers，把每个 server 的工具以 mcp_{server}_{tool}
 * 注册进 ToolRegistry（只读，经 SafetyInterceptor 审计）。单个 server 失败只告警，不影响应用与其余 server。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpToolBridge implements SmartLifecycle {

    private final McpProperties properties;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final List<McpConnection> connections = new ArrayList<>();
    private volatile boolean running = false;

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            log.info("MCP 未启用 (travel.mcp.enabled=false)，仅使用内置工具");
            return;
        }
        running = true;
        for (McpProperties.Server server : properties.getServers()) {
            connect(server);
        }
    }

    private void connect(McpProperties.Server server) {
        try {
            McpTransport transport = server.getUrl() != null && !server.getUrl().isBlank()
                    ? new HttpMcpTransport(server.getUrl(), server.getTimeoutMs())
                    : new StdioMcpTransport(server.getCommand(), server.getEnv(), server.getTimeoutMs());
            McpConnection connection = new McpConnection(transport, objectMapper);
            connection.initialize("travel-agent", "0.1.0");
            int registered = 0;
            for (McpConnection.McpToolDef def : connection.listTools()) {
                String registryName = "mcp_" + sanitize(server.getName()) + "_" + sanitize(def.name());
                AgentTool tool = new McpAgentTool(registryName, def.name(),
                        "(MCP:" + server.getName() + ") " + orDefault(def.description(), def.name()) + paramHint(def.inputSchema()),
                        server.isReadOnly(), connection);
                if (toolRegistry.register(tool)) {
                    registered++;
                }
            }
            connections.add(connection);
            log.info("MCP 服务器 [{}] ({}) 已接入，注册 {} 个工具", server.getName(),
                    connection.serverName(), registered);
        } catch (Exception e) {
            log.warn("MCP 服务器 [{}] 接入失败，已跳过: {}", server.getName(), e.getMessage());
        }
    }

    /** 从 inputSchema 提取参数名清单，追加到工具描述里供 LLM 生成参数 */
    private String paramHint(JsonNode inputSchema) {
        JsonNode properties = inputSchema == null ? null : inputSchema.path("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        JsonNode required = inputSchema.path("required");
        List<String> requiredNames = new ArrayList<>();
        if (required.isArray()) {
            required.forEach(n -> requiredNames.add(n.asText()));
        }
        StringBuilder hint = new StringBuilder("（参数: ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                hint.append(", ");
            }
            String param = names.get(i);
            hint.append(param).append(requiredNames.contains(param) ? "*" : "");
        }
        return hint.append(requiredNames.isEmpty() ? "）" : "；*为必填）").toString();
    }

    private String sanitize(String raw) {
        String cleaned = (raw == null ? "srv" : raw).toLowerCase()
                .replaceAll("[^a-z0-9_]", "_");
        if (cleaned.isBlank() || cleaned.equals("_")) {
            cleaned = "srv";
        }
        return cleaned.length() > 20 ? cleaned.substring(0, 20) : cleaned;
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void stop() {
        running = false;
        for (McpConnection connection : connections) {
            try {
                connection.close();
            } catch (Exception e) {
                log.warn("MCP 连接关闭失败: {}", e.getMessage());
            }
        }
        connections.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
