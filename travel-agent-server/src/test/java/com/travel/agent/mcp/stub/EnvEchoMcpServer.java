package com.travel.agent.mcp.stub;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试用 stdio MCP server stub（纯 JDK，无 Jackson），专门验证「command + args + env」是否原样传到子进程，
 * 以及工具名是否按 mcp_&lt;server&gt;_&lt;tool&gt; 注册。
 *
 * <p>参数约定：
 * <ul>
 *   <li>{@code args[0]}：本 server 对外暴露的工具名（例如 {@code maps_geo}）——
 *       能出现在 tools/list 里，就证明 args 数组没有在启动时被错误拆分/丢弃；</li>
 *   <li>{@code args[1]}：要读取的环境变量名；</li>
 *   <li>{@code args[2]}：该环境变量的期望值。三者齐备说明整个 args 数组都传到了子进程。</li>
 * </ul>
 *
 * <p>tools/call 返回 {@code ENV_OK:<tool>|<address>}（环境变量匹配）或 {@code ENV_MISMATCH}
 * （未匹配，或 args[1]/args[2] 缺失）。刻意<strong>不回显</strong>环境变量原值，避免任何 Key 进日志。
 */
public final class EnvEchoMcpServer {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ADDRESS = Pattern.compile("\"address\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private EnvEchoMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        String exposedTool = args.length > 0 ? args[0] : "env_probe";
        String envName = args.length > 1 ? args[1] : "AMAP_MAPS_API_KEY";
        String expectedEnv = args.length > 2 ? args[2] : "";
        System.err.println("[env-stub] started tool=" + exposedTool + " argc=" + args.length);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.contains("\"id\"")) {
                continue; // notifications/initialized 等通知不响应
            }
            Matcher idMatcher = ID.matcher(line);
            Matcher methodMatcher = METHOD.matcher(line);
            if (!idMatcher.find() || !methodMatcher.find()) {
                continue;
            }
            String id = idMatcher.group(1);
            String result = switch (methodMatcher.group(1)) {
                case "initialize" -> "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"amap-env-stub\",\"version\":\"1.0\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"" + exposedTool + "\","
                        + "\"description\":\"高德风格工具：城市/地址解析为坐标\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"address\":{\"type\":\"string\",\"description\":\"待解析的结构化地址信息\"},"
                        + "\"city\":{\"type\":\"string\",\"description\":\"指定查询的城市\"}},"
                        + "\"required\":[\"address\"]}},"
                        // 第二个工具刻意照抄高德 maps_distance 的真实 schema：
                        // 参数说明里含 ASCII 逗号/括号/全角分号（真实包 build/index.js 就是这样），
                        // 用来验证参数提示格式不被这些字符打断。
                        + "{\"name\":\"maps_distance\","
                        + "\"description\":\"距离测量\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"origins\":{\"type\":\"string\",\"description\":\"起点经纬度，格式: lon,lat\"},"
                        + "\"destination\":{\"type\":\"string\",\"description\":\"终点经纬度,支持多个；用|分隔\"},"
                        + "\"type\":{\"type\":\"number\",\"description\":\"0:直线距离,2:步行(含摆渡),3:驾车\"}},"
                        + "\"required\":[\"origins\",\"destination\"]}}]}";
                case "tools/call" -> envCheckResult(envName, expectedEnv, exposedTool, line);
                default -> null;
            };
            if (result == null) {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
            } else {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
            }
        }
        System.err.println("[env-stub] stdin closed, exiting");
    }

    private static String envCheckResult(String envName, String expectedEnv, String exposedTool, String line) {
        String actual = System.getenv(envName);
        boolean matched = expectedEnv != null && !expectedEnv.isEmpty() && expectedEnv.equals(actual);
        Matcher address = ADDRESS.matcher(line);
        String text = matched
                ? "ENV_OK:" + exposedTool + "|" + (address.find() ? address.group(1) : "")
                : "ENV_MISMATCH";
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }
}