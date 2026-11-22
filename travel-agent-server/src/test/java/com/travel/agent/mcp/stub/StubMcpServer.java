package com.travel.agent.mcp.stub;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试用 stdio MCP server stub：按行读 JSON-RPC，按 MCP 协议回答 initialize / tools/list / tools/call。
 * 纯 JDK 实现（不依赖 Jackson），字段用正则提取，避免子进程类路径问题干扰传输层测试。
 * 仅测试进程使用（src/test/java），通过 java -cp 由 StdioMcpTransport 拉起。
 */
public final class StubMcpServer {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private StubMcpServer() { }

    public static void main(String[] args) throws Exception {
        System.err.println("[stub] started");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            System.err.println("[stub] received: " + line);
            if (!line.contains("\"id\"")) {
                continue; // 通知（notifications/initialized），不响应
            }
            Matcher idMatcher = ID.matcher(line);
            Matcher methodMatcher = METHOD.matcher(line);
            if (!idMatcher.find() || !methodMatcher.find()) {
                System.err.println("[stub] 无法解析 id/method，跳过");
                continue;
            }
            String id = idMatcher.group(1);
            String result;
            switch (methodMatcher.group(1)) {
                case "initialize" -> result = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"stdio-stub\",\"version\":\"1.0\"}}";
                case "tools/list" -> result = "{\"tools\":[{\"name\":\"echo\",\"description\":\"原样返回输入文本\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}]}";
                case "tools/call" -> {
                    Matcher textMatcher = TEXT.matcher(line);
                    String text = textMatcher.find() ? textMatcher.group(1) : "";
                    result = "{\"content\":[{\"type\":\"text\",\"text\":\"echo:" + text + "\"}]}";
                }
                default -> result = null;
            }
            if (result == null) {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
            } else {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
            }
            System.err.println("[stub] replied id=" + id);
        }
        System.err.println("[stub] stdin closed, exiting");
    }
}
