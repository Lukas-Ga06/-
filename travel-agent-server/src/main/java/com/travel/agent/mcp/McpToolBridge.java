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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP 桥接：启动时逐个接入 travel.mcp.servers，把每个 server 的工具以 mcp_{server}_{tool}
 * 注册进 ToolRegistry（只读，经 SafetyInterceptor 审计）。单个 server 失败只告警，不影响应用与其余 server。
 *
 * <p>P0-A：同时维护一份线程安全的接入状态快照（{@link McpStatusSnapshot}），供
 * {@code /api/system/status} 如实自报「启用了没有 / 连上了几个 server / 注册了几个工具 / 最近错误」，
 * 运维不必再翻启动日志猜 MCP 是否生效。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpToolBridge implements SmartLifecycle {

    /** 单个参数说明截断长度：够模型理解，又不至于把提示词撑爆 */
    private static final int MAX_PARAM_DESC = 30;
    /** 注册名里 server 段的最大长度（4+20+1+39 = 64，等于主流 LLM 函数名上限） */
    static final int MAX_SERVER_NAME = 20;
    /** 注册名里 tool 段的最大长度：要容下 maps_direction_transit_integrated（33 字符） */
    static final int MAX_TOOL_NAME = 39;
    /** 状态快照里的错误摘要截断（避免把整段堆栈带进 status 响应） */
    private static final int MAX_ERROR_LENGTH = 300;

    private final McpProperties properties;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final List<McpConnection> connections = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    /** 一个 MCP server 的接入结果（status 数组里的一行） */
    public record McpServerStatus(String name, boolean connected, int toolCount, String lastError) { }

    /**
     * 接入状态快照（不可变，volatile 引用整体替换）。
     *
     * @param enabled   配置是否启用 MCP
     * @param running   桥接是否已启动（start 完成且未 stop）
     * @param toolCount 已注册的 MCP 工具总数
     * @param servers   每个配置 server 的接入结果
     * @param lastError 最近一次接入失败原因（多个失败取第一个），全部成功为 null
     */
    public record McpStatusSnapshot(boolean enabled, boolean running, int toolCount,
                                    List<McpServerStatus> servers, String lastError) {
    }

    /** 未启动/未启用时的空快照 */
    private static final McpStatusSnapshot NOT_STARTED =
            new McpStatusSnapshot(false, false, 0, List.of(), null);

    private volatile McpStatusSnapshot status = NOT_STARTED;

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            List<McpServerStatus> configured = new ArrayList<>();
            for (McpProperties.Server server : properties.getServers()) {
                configured.add(new McpServerStatus(displayName(server), false, 0, null));
            }
            status = new McpStatusSnapshot(false, false, 0, List.copyOf(configured), null);
            log.info("MCP 未启用 (travel.mcp.enabled=false)，仅使用内置工具");
            return;
        }
        running = true;
        List<McpServerStatus> serverStatuses = new ArrayList<>();
        for (McpProperties.Server server : properties.getServers()) {
            serverStatuses.add(connect(server));
        }
        int totalTools = serverStatuses.stream().mapToInt(McpServerStatus::toolCount).sum();
        String lastError = serverStatuses.stream()
                .map(McpServerStatus::lastError)
                .filter(error -> error != null && !error.isBlank())
                .findFirst()
                .orElse(null);
        status = new McpStatusSnapshot(true, true, totalTools, List.copyOf(serverStatuses), lastError);
        log.info("MCP 接入完成：{} 个 server，共 {} 个工具{}", serverStatuses.size(), totalTools,
                lastError == null ? "" : "（最近错误：" + lastError + "）");
    }

    private McpServerStatus connect(McpProperties.Server server) {
        String name = displayName(server);
        McpConnection connection = null;
        try {
            McpTransport transport = server.getUrl() != null && !server.getUrl().isBlank()
                    ? new HttpMcpTransport(server.getUrl(), server.getTimeoutMs())
                    : new StdioMcpTransport(server.getCommand(), server.getEnv(), server.getTimeoutMs());
            transport.start();
            connection = new McpConnection(transport, objectMapper);
            connection.initialize("travel-agent", "0.1.0");
            int registered = 0;
            for (McpConnection.McpToolDef def : connection.listTools()) {
                String registryName = "mcp_" + sanitize(server.getName(), MAX_SERVER_NAME)
                        + "_" + sanitize(def.name(), MAX_TOOL_NAME);
                AgentTool tool = new McpAgentTool(registryName, def.name(),
                        "(MCP:" + server.getName() + ") " + orDefault(def.description(), def.name()) + paramHint(def.inputSchema()),
                        server.isReadOnly(), connection);
                if (toolRegistry.register(tool)) {
                    registered++;
                } else {
                    // 重名（另一个 server 或内置工具已占用）：拒绝注册但不影响其余工具与其它 server
                    log.warn("MCP 工具 {} 与已注册工具重名，已跳过（server={}, 远端工具={}）",
                            registryName, server.getName(), def.name());
                }
            }
            connections.add(connection);
            log.info("MCP 服务器 [{}] ({}) 已接入，注册 {} 个工具", server.getName(),
                    connection.serverName(), registered);
            return new McpServerStatus(name, true, registered, null);
        } catch (Exception e) {
            // 单个 server 失败只告警：不阻断应用启动，也不影响其余 server
            String error = truncateError(e);
            log.warn("MCP 服务器 [{}] 接入失败，已跳过: {}", server.getName(), error);
            closeQuietly(connection, server.getName());
            return new McpServerStatus(name, false, 0, error);
        }
    }

    /** 当前接入状态快照（线程安全：整体替换的不可变对象，可被 status 接口任意线程读取） */
    public McpStatusSnapshot status() {
        return status;
    }

    private static String displayName(McpProperties.Server server) {
        return server.getName() == null || server.getName().isBlank() ? "unnamed" : server.getName();
    }

    private static String truncateError(Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) + "…" : message;
    }

    /** 握手/枚举中途失败时回收已拉起的 stdio 子进程，避免测试与运行期泄漏进程 */
    private void closeQuietly(McpConnection connection, String serverName) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception e) {
            log.debug("MCP 服务器 [{}] 失败后清理连接异常: {}", serverName, e.getMessage());
        }
    }

    /**
     * 从 inputSchema 提取参数名清单，追加到工具描述里供 LLM 生成参数：
     * 必填项加 {@code *}，并把每个参数的 description（高德包里是中文说明）截断后一并带上，
     * 避免模型只知道参数名却不知道填什么。
     */
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
            String paramDesc = properties.path(param).path("description").asText("");
            if (!paramDesc.isBlank()) {
                paramDesc = sanitizeParamDesc(paramDesc);
                if (paramDesc.length() > MAX_PARAM_DESC) {
                    paramDesc = paramDesc.substring(0, MAX_PARAM_DESC) + "…";
                }
                hint.append('(').append(paramDesc).append(')');
            }
        }
        return hint.append(requiredNames.isEmpty() ? "）" : "；*为必填）").toString();
    }

    /**
     * 参数说明里可能带 {@code , ( ) ；}（高德 {@code maps_distance} 的 type 说明就含逗号和括号），
     * 直接拼进「（参数: a*(说明), b）」会打断这个格式、也会打断下游对必填标记的解析。
     * 因此统一换成全角/等价字符，保证 ASCII 逗号只作参数分隔、全角「；」只作必填段分隔。
     */
    static String sanitizeParamDesc(String raw) {
        return raw.replaceAll("[\\r\\n]+", " ").trim()
                .replace('(', '（')
                .replace(')', '）')
                .replace(',', '，')
                .replace('；', ';');
    }

    /**
     * 规范化名字片段：小写、非 [a-z0-9_] 换成下划线、限制最大长度。
     *
     * <p>长度上限的意义：注册名总长 {@code mcp_ + server + _ + tool} 必须留在 LLM 函数名上限内
     * （主流模型 64 字符）。因此 server 段最多 {@value #MAX_SERVER_NAME}、tool 段最多
     * {@value #MAX_TOOL_NAME}，合计 4+20+1+39=64。tool 段给到 39 是为了保住真实工具名——
     * 高德包的 {@code maps_direction_transit_integrated}（33 字符）若按旧的 20 截断，
     * 会变成 {@code maps_direction_trans}，既丢语义又让适配层无法按真名映射。
     */
    static String sanitize(String raw, int maxLength) {
        String cleaned = (raw == null ? "srv" : raw).toLowerCase()
                .replaceAll("[^a-z0-9_]", "_");
        if (cleaned.isBlank() || cleaned.equals("_")) {
            cleaned = "srv";
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
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
        // 关闭后不再有可用连接：快照保留「启用过」的事实，但报告为未连接
        List<McpServerStatus> stopped = status.servers().stream()
                .map(s -> new McpServerStatus(s.name(), false, 0, s.lastError()))
                .toList();
        status = new McpStatusSnapshot(status.enabled(), false, 0, stopped, status.lastError());
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
