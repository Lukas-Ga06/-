package com.travel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.travel.agent.config.McpProperties;
import com.travel.agent.mcp.stub.StubMcpServer;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 桥接端到端：HTTP 传输用内置 HttpServer stub；stdio 传输起真实 Java 子进程。
 * 验证 initialize 握手、tools/list 注册进 ToolRegistry、tools/call 返回文本、重复注册被拒。
 */
class McpBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong REQ_ID = new AtomicLong();

    @AfterAll
    static void cleanup() {
        // 无共享状态
    }

    @Test
    void httpServerToolsAreRegisteredAndCallable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            try {
                JsonNode req = MAPPER.readTree(body);
                if (!req.has("id") || req.path("id").isNull()) {
                    // 通知（notifications/initialized）：202 无响应体
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    return;
                }
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("jsonrpc", "2.0");
                resp.set("id", req.path("id"));
                switch (req.path("method").asText()) {
                    case "initialize" -> {
                        exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
                        ObjectNode result = resp.putObject("result");
                        result.put("protocolVersion", "2024-11-05");
                        result.putObject("capabilities").putObject("tools");
                        result.putObject("serverInfo").put("name", "stub-http").put("version", "1.0");
                    }
                    case "tools/list" -> {
                        ObjectNode result = resp.putObject("result");
                        ObjectNode tool = result.putArray("tools").addObject();
                        tool.put("name", "web_search");
                        tool.put("description", "搜索网页并返回摘要");
                        ObjectNode schema = tool.putObject("inputSchema");
                        schema.put("type", "object");
                        schema.putObject("properties").putObject("query").put("type", "string");
                        schema.putArray("required").add("query");
                    }
                    case "tools/call" -> {
                        ObjectNode result = resp.putObject("result");
                        result.putArray("content").addObject()
                                .put("type", "text")
                                .put("text", "stub-result about " + req.path("params").path("arguments").path("query").asText(""));
                    }
                    default -> {
                        ObjectNode error = resp.putObject("error");
                        error.put("code", -32601);
                        error.put("message", "method not found");
                    }
                }
                byte[] out = resp.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            McpProperties props = new McpProperties();
            props.setEnabled(true);
            McpProperties.Server serverCfg = new McpProperties.Server();
            serverCfg.setName("stub");
            serverCfg.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            serverCfg.setReadOnly(true);
            props.setServers(List.of(serverCfg));

            ToolRegistry registry = new ToolRegistry(List.of());
            McpToolBridge bridge = new McpToolBridge(props, registry, MAPPER);
            bridge.start();
            try {
                AgentTool tool = registry.get("mcp_stub_web_search");
                assertNotNull(tool, "MCP 工具应注册进 ToolRegistry");
                assertTrue(tool.readOnly());
                assertTrue(tool.getDescription().contains("参数: query*"));
                assertEquals("stub-result about 京都", tool.execute(Map.of("query", "京都")));
                assertFalse(registry.register(tool), "同名工具不允许重复注册");
            } finally {
                bridge.stop();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stdioSubprocessToolsAreCallable() throws Exception {
        List<String> command = List.of(
                System.getProperty("java.home") + java.io.File.separator + "bin"
                        + java.io.File.separator + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java"),
                "-cp", System.getProperty("java.class.path"),
                StubMcpServer.class.getName());
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpProperties.Server serverCfg = new McpProperties.Server();
        serverCfg.setName("echo");
        serverCfg.setCommand(command);
        serverCfg.setTimeoutMs(15000);
        props.setServers(List.of(serverCfg));

        ToolRegistry registry = new ToolRegistry(List.of());
        McpToolBridge bridge = new McpToolBridge(props, registry, MAPPER);
        bridge.start();
        try {
            AgentTool tool = registry.get("mcp_echo_echo");
            assertNotNull(tool, "stdio 子进程的 MCP 工具应注册进 ToolRegistry");
            assertEquals("echo:你好", tool.execute(Map.of("text", "你好")));
        } finally {
            bridge.stop();
        }
    }

    @Test
    void unreachableServerIsSkippedAndAppContinues() {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpProperties.Server bad = new McpProperties.Server();
        bad.setName("dead");
        bad.setUrl("http://127.0.0.1:1/mcp");
        bad.setTimeoutMs(2000);
        props.setServers(List.of(bad));

        ToolRegistry registry = new ToolRegistry(List.of());
        McpToolBridge bridge = new McpToolBridge(props, registry, MAPPER);
        bridge.start();
        assertTrue(registry.all().isEmpty(), "接入失败的 server 不应注册任何工具");
        bridge.stop();
    }
}
