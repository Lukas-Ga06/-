package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）工具生态配置。
 * 把外部 MCP server 的工具注册进 ToolRegistry（只读），LLM 意图识别可将「查外部数据」路由到 mcp_* 工具。
 * 传输支持 streamable HTTP（url）与 stdio（command，本机子进程，如 npx 启动的高德地图 MCP）。
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

        /** 命名空间：注册后的工具名为 mcp_{name}_{tool} */
        private String name;

        /** streamable HTTP 端点（与 command 二选一） */
        private String url;

        /** stdio 启动命令，如 ["cmd","/c","npx","-y","@amap/amap-maps-mcp-server"]（Windows 需 cmd /c 前缀） */
        private List<String> command;

        /** stdio 子进程的环境变量（如高德 Key，建议用环境变量占位注入，不落仓库） */
        private Map<String, String> env;

        /** 注册为只读工具（默认 true；架构约定工具层只允许只读，写操作必须走 AgentPlan 确认） */
        private boolean readOnly = true;

        /** 单次请求超时（毫秒），stdio 也用作子进程响应等待 */
        private long timeoutMs = 20000;
    }
}
