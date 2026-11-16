package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）工具生态配置。
 * 把外部 MCP server 的工具注册进 ToolRegistry（只读），LLM 意图识别可将「查外部数据」路由到 mcp_* 工具。
 * 传输支持 streamable HTTP（url）与 stdio（command，本机子进程，如 npx 启动的高德地图 MCP）。
 *
 * <p>对应 yml 结构（{@code travel.mcp}）：
 * <pre>
 * travel:
 *   mcp:
 *     enabled: ${MCP_ENABLED:false}          # 总开关，默认关闭；关闭时不拉起任何子进程
 *     servers:
 *       - name: amap                          # 命名空间，工具注册为 mcp_amap_&lt;tool&gt;
 *         command: ["npx", "-y", "@amap/amap-maps-mcp-server"]   # stdio；Windows 会自动补 cmd /c
 *         env: { AMAP_MAPS_API_KEY: "${AMAP_MAPS_API_KEY:}" }    # 子进程环境变量，Key 不落仓库
 *         read-only: true                     # 默认 true；写工具会被 ToolRegistry 拒绝
 *         timeout-ms: 20000                   # 单次 JSON-RPC 等待（含握手）
 *       - name: wiki
 *         url: http://localhost:8807/mcp      # 与 command 二选一（配置了 url 就走 HTTP）
 * </pre>
 *
 * 所有新字段都有默认值：既有的 {@code travel.mcp.servers} 配置不写 env / timeout-ms 也能照常启动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.mcp")
public class McpProperties {

    /** 默认关闭；开启后启动时逐个接入 servers，失败的服务器只告警不阻断应用 */
    private boolean enabled = false;

    private List<Server> servers = new ArrayList<>();

    @Data
    public static class Server {

        /** 命名空间：注册后的工具名为 mcp_{name}_{tool}；重复的 name 会让后者工具被拒绝注册 */
        private String name;

        /** streamable HTTP 端点（与 command 二选一；非空时优先走 HTTP） */
        private String url;

        /**
         * stdio 启动命令（命令 + 参数数组，避免 {@code npx -y ...} 被错误拆分）。
         * 例：{@code ["npx","-y","@amap/amap-maps-mcp-server"]}；
         * Windows 上若首项是 npx/npm/pnpm/yarn 或 *.cmd/*.bat，启动时会自动补 {@code cmd /c}。
         */
        private List<String> command;

        /** stdio 子进程的环境变量（如高德 Key，建议用 ${ENV:} 占位注入，不落仓库） */
        private Map<String, String> env = new LinkedHashMap<>();

        /** 注册为只读工具（默认 true；架构约定工具层只允许只读，写操作必须走 AgentPlan 确认） */
        private boolean readOnly = true;

        /** 单次请求超时（毫秒），stdio 也用作子进程响应等待与握手等待 */
        private long timeoutMs = 20000;
    }
}
