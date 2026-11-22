package com.travel.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.config.McpProperties;
import com.travel.agent.mcp.stub.EnvEchoMcpServer;
import com.travel.agent.mcp.stub.FailingMcpServer;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * stdio 链路专项测试（无需真实高德 Key）：
 * <ol>
 *   <li>command + args + env 原样传到子进程，工具按 mcp_&lt;server&gt;_&lt;tool&gt; 注册；</li>
 *   <li>缺 Key 导致子进程启动即退出时，桥接只告警、不抛异常、不注册任何工具；</li>
 *   <li>同名 server 的重复工具注册被拒绝，不影响其余工具。</li>
 * </ol>
 * 子进程用 src/test/java 里的 {@link EnvEchoMcpServer} / {@link FailingMcpServer}（纯 JDK，无第三方依赖）。
 */
class McpStdioEnvTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 测试用占位值：不是真实 Key，只为证明 env 传递；子进程只做等值比较，不回显原值 */
    private static final String PROBE_ENV_NAME = "AMAP_MAPS_API_KEY";
    private static final String PROBE_ENV_VALUE = "test-placeholder-key-not-real";

    private static List<String> javaCommand(Class<?> mainClass, String... extraArgs) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        List<String> command = new java.util.ArrayList<>(List.of(
                javaBin, "-cp", System.getProperty("java.class.path"), mainClass.getName()));
        command.addAll(List.of(extraArgs));
        return command;
    }

    @Test
    void commandArgsAndEnvArePassedToStdioSubprocess() {
        // args[0]=暴露的工具名（与高德真实工具同名，便于后续适配层复用）args[1]=env 名 args[2]=env 期望值
        List<String> command = javaCommand(EnvEchoMcpServer.class,
                "maps_geo", PROBE_ENV_NAME, PROBE_ENV_VALUE);

        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpProperties.Server server = new McpProperties.Server();
        server.setName("amap");
        server.setCommand(command);
        server.setEnv(Map.of(PROBE_ENV_NAME, PROBE_ENV_VALUE));
        server.setTimeoutMs(20000);
        props.setServers(List.of(server));

        ToolRegistry registry = new ToolRegistry(List.of());
        McpToolBridge bridge = new McpToolBridge(props, registry, MAPPER);
        bridge.start();
        try {
            AgentTool tool = registry.get("mcp_amap_maps_geo");
            assertNotNull(tool, "工具应按 mcp_amap_<tool> 注册");
            assertTrue(tool.readOnly(), "MCP 工具默认只读");
            // 参数提示覆盖必填标记 + 参数说明（来自 inputSchema 的 description）
            String description = tool.getDescription();
            assertTrue(description.contains("参数: address*(待解析的结构化地址信息)"),
                    "参数提示应带必填标记与说明，实际: " + description);
            assertTrue(description.contains("city(指定查询的城市)"), "实际: " + description);
            assertTrue(description.contains("*为必填"), "实际: " + description);

            // ENV_OK 同时证明：env 传给了子进程、args 全部原样到达（工具名/环境变量名/期望值）
            assertEquals("ENV_OK:maps_geo|建国路88号",
                    tool.execute(Map.of("address", "建国路88号")));

            // 参数说明里带 ASCII 逗号/括号时，参数提示仍必须是可解析的单行格式（照抄高德 maps_distance 真实 schema）
            AgentTool distance = registry.get("mcp_amap_maps_distance");
            assertNotNull(distance, "第二个工具也应注册");
            String distanceDesc = distance.getDescription();
            assertTrue(distanceDesc.contains("参数: origins*(起点经纬度，格式: lon，lat)"),
                    "参数说明里的逗号/括号应转全角，实际: " + distanceDesc);
            AmapMcpAdapter.ParamSummary parsed = AmapMcpAdapter.parseParamHint(distanceDesc);
            assertEquals(List.of("origins", "destination", "type"), parsed.params(), "实际: " + distanceDesc);
            assertEquals(List.of("origins", "destination"), parsed.required(), "实际: " + distanceDesc);
        } finally {
            bridge.stop();
        }
    }

    @Test
    void longRealAmapToolNameSurvivesRegistrationAndAdapterMapping() {
        // 高德真实工具名 33 字符：旧的 20 字符截断会把它砍成 maps_direction_trans，适配层就认不出来了
        List<String> command = javaCommand(EnvEchoMcpServer.class,
                "maps_direction_transit_integrated", PROBE_ENV_NAME, PROBE_ENV_VALUE);
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpProperties.Server server = new McpProperties.Server();
        server.setName("amap");
        server.setCommand(command);
        server.setEnv(Map.of(PROBE_ENV_NAME, PROBE_ENV_VALUE));
        props.setServers(List.of(server));

        ToolRegistry registry = new ToolRegistry(List.of());
        McpToolBridge bridge = new McpToolBridge(props, registry, MAPPER);
        bridge.start();
        try {
            String expected = "mcp_amap_maps_direction_transit_integrated";
            assertNotNull(registry.get(expected), "长工具名不应被截断，实际注册: " + registry.all().keySet());
            assertTrue(expected.length() <= 64, "注册名要留在 LLM 函数名上限 64 内");

            AmapMcpAdapter adapter = new AmapMcpAdapter();
            assertEquals(java.util.Optional.of(expected),
                    adapter.registryName(registry, AmapMcpAdapter.Capability.ROUTE_TRANSIT));
            String prompt = adapter.promptSummary(registry);
            assertTrue(prompt.contains("route_transit"), prompt);
            assertTrue(prompt.contains("mcp_amap_maps_direction_transit_integrated"), prompt);
        } finally {
            bridge.stop();
        }
    }

    @Test
    void stdioServerExitingAtStartupOnlyWarns() {
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpProperties.Server broken = new McpProperties.Server();
        broken.setName("amap-no-key");
        // 复现真实高德包缺 Key 的行为：stderr 报错 + exit(1)，没有 initialize 响应
        broken.setCommand(javaCommand(FailingMcpServer.class));
        broken.setTimeoutMs(3000);
        props.setServers(List.of(broken));

        ToolRegistry registry = new ToolRegistry(List.of());
        McpToolBridge bridge = new McpToolBridge(props, registry, MAPPER);
        bridge.start();
        try {
            assertTrue(registry.all().isEmpty(), "启动即退出的 server 不应注册任何工具");
            assertTrue(bridge.isRunning(), "桥接本身仍在运行，单个 server 失败不阻断应用");
            assertNull(registry.get("mcp_amap_no_key_maps_geo"));
        } finally {
            bridge.stop();
        }
    }

    @Test
    void duplicateServerNameDoesNotDoubleRegisterTools() {
        List<String> command = javaCommand(EnvEchoMcpServer.class,
                "maps_geo", PROBE_ENV_NAME, PROBE_ENV_VALUE);
        McpProperties props = new McpProperties();
        props.setEnabled(true);
        McpProperties.Server first = new McpProperties.Server();
        first.setName("amap");
        first.setCommand(command);
        first.setEnv(Map.of(PROBE_ENV_NAME, PROBE_ENV_VALUE));
        first.setTimeoutMs(20000);
        McpProperties.Server second = new McpProperties.Server();
        second.setName("amap");
        second.setCommand(command);
        second.setEnv(Map.of(PROBE_ENV_NAME, PROBE_ENV_VALUE));
        second.setTimeoutMs(20000);
        props.setServers(List.of(first, second));

        ToolRegistry registry = new ToolRegistry(List.of());
        McpToolBridge bridge = new McpToolBridge(props, registry, MAPPER);
        bridge.start();
        try {
            // 两个 name 都叫 amap 的 server 注册同一批工具：唯一工具各注册一次（stub 暴露 2 个工具）
            assertEquals(2, registry.all().size(), "同名 server 的重复工具只应注册一次，实际: " + registry.all().keySet());
            assertNotNull(registry.get("mcp_amap_maps_geo"));
        } finally {
            bridge.stop();
        }
    }

    @Test
    void windowsCommandShimWrapsNpxButLeavesPlainExecutables() {
        List<String> npx = List.of("npx", "-y", "@amap/amap-maps-mcp-server");
        List<String> resolved = StdioMcpTransport.resolveCommand(npx);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            // npx 在 Windows 上是 npx.cmd，ProcessBuilder 不能直接执行，必须补 cmd /c
            assertEquals(List.of("cmd", "/c", "npx", "-y", "@amap/amap-maps-mcp-server"), resolved);
            // 已显式写了 cmd /c 的原样保留，不重复包装
            List<String> explicit = List.of("cmd", "/c", "npx", "-y", "@amap/amap-maps-mcp-server");
            assertEquals(explicit, StdioMcpTransport.resolveCommand(explicit));
        } else {
            assertEquals(npx, resolved);
        }
        // 普通可执行文件（java）两种平台都原样返回
        List<String> java = List.of("java", "-version");
        assertEquals(java, StdioMcpTransport.resolveCommand(java));
        // 空配置不抛异常
        assertTrue(StdioMcpTransport.resolveCommand(null) == null);
    }
}