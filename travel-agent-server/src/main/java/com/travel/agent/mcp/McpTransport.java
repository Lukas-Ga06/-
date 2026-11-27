package com.travel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 传输层：负责把一条 JSON-RPC 请求送达 server 并取回响应（通知不等待响应）。
 * 协议细节（initialize / tools/list / tools/call）在 {@link McpConnection}，这里只管收发。
 */
public interface McpTransport extends AutoCloseable {

    /** 启动后台接收资源；无后台线程的传输可使用默认空实现。 */
    default void start() {
    }

    /** 发送带 id 的请求并等待匹配的响应；超时/连接失败抛异常 */
    JsonNode request(JsonNode requestBody) throws Exception;

    /** 发送通知（无 id，不等待响应） */
    void notify(JsonNode notification) throws Exception;

    @Override
    void close();
}
