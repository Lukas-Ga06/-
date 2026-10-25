package com.travel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议层：initialize 握手 → tools/list → tools/call。
 * 一个连接对应一个 MCP server；底层传输可为 streamable HTTP 或 stdio。
 */
@Slf4j
public class McpConnection implements AutoCloseable {

    /** 选取与常见 server 兼容性最好的协议版本 */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    public record McpToolDef(String name, String description, JsonNode inputSchema) { }

    private final McpTransport transport;
    private final ObjectMapper mapper;
    private String serverName;

    public McpConnection(McpTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    public void initialize(String clientName, String clientVersion) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", clientName);
        clientInfo.put("version", clientVersion);
        JsonNode response = transport.request(request("initialize", params));
        checkError(response);
        this.serverName = response.path("result").path("serverInfo").path("name").asText("unknown");
        transport.notify(mapper.createObjectNode().put("method", "notifications/initialized"));
        log.info("MCP initialize 完成: server={}, protocol={}", serverName,
                response.path("result").path("protocolVersion").asText(PROTOCOL_VERSION));
    }

    public List<McpToolDef> listTools() throws Exception {
        JsonNode response = transport.request(request("tools/list", mapper.createObjectNode()));
        checkError(response);
        List<McpToolDef> tools = new ArrayList<>();
        JsonNode array = response.path("result").path("tools");
        if (array.isArray()) {
            for (JsonNode tool : array) {
                tools.add(new McpToolDef(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.path("inputSchema")));
            }
        }
        return tools;
    }

    /** 执行工具并拼接文本内容；server 返回 isError 或抛错时给出带原因的异常 */
    public String callTool(String toolName, Map<String, Object> args) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        ObjectNode arguments = params.putObject("arguments");
        if (args != null) {
            args.forEach((key, value) -> arguments.set(key, mapper.valueToTree(value)));
        }
        JsonNode response = transport.request(request("tools/call", params));
        checkError(response);
        JsonNode result = response.path("result");
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException(firstText(result).isEmpty() ? "工具执行失败" : firstText(result));
        }
        String text = firstText(result);
        if (!text.isEmpty()) {
            return text;
        }
        // 宽松兼容：没有 content 的 server 直接回传结构化结果
        JsonNode structured = result.has("structuredContent") ? result.path("structuredContent") : result;
        return structured.isMissingNode() || structured.isNull() ? "" : mapper.writeValueAsString(structured);
    }

    private String firstText(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(part.path("text").asText());
                }
            }
        }
        return sb.toString().trim();
    }

    private ObjectNode request(String method, ObjectNode params) {
        ObjectNode request = mapper.createObjectNode();
        request.put("method", method);
        request.set("params", params);
        return request;
    }

    private void checkError(JsonNode response) throws Exception {
        JsonNode error = response.path("error");
        if (error.isObject()) {
            throw new IllegalStateException("MCP 错误 " + error.path("code").asInt() + ": "
                    + error.path("message").asText("unknown"));
        }
    }

    public String serverName() {
        return serverName;
    }

    @Override
    public void close() {
        transport.close();
    }
}
