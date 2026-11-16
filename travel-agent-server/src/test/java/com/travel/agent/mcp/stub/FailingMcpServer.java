package com.travel.agent.mcp.stub;

/**
 * 测试用 stdio MCP server stub：启动后立刻向 stderr 打一条「高德缺少 Key」风格的报错并以 1 退出，
 * 用来复现真实 @amap/amap-maps-mcp-server 在没有 AMAP_MAPS_API_KEY 时的行为
 * （真实包在模块加载期就 process.exit(1)，根本走不到 initialize 握手）。
 */
public final class FailingMcpServer {

    private FailingMcpServer() {
    }

    public static void main(String[] args) {
        System.err.println("AMAP_MAPS_API_KEY environment variable is not set");
        System.exit(1);
    }
}