package com.travel.agent.channel;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 微信 API 客户端：access_token 缓存复用、errcode 40001 强刷重试、发送失败不抛出 */
class WeChatClientTest {

    private HttpServer server;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger sendCalls = new AtomicInteger();
    private final List<String> sendBodies = new ArrayList<>();
    /** send 端第 N 次调用返回的 errcode（默认 0） */
    private final List<Integer> sendErrcodes = new ArrayList<>();

    private WeChatClient startClient() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/cgi-bin/token", exchange -> {
            int call = tokenCalls.incrementAndGet();
            byte[] resp = (call == 1
                    ? "{\"access_token\":\"token-1\",\"expires_in\":7200}"
                    : "{\"access_token\":\"token-2\",\"expires_in\":7200}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/cgi-bin/message/custom/send", exchange -> {
            sendBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = sendCalls.incrementAndGet();
            int errcode = call <= sendErrcodes.size() ? sendErrcodes.get(call - 1) : 0;
            byte[] resp = ("{\"errcode\":" + errcode + ",\"errmsg\":\""
                    + (errcode == 0 ? "ok" : "access_token expired") + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return new WeChatClient("wx-app", "wx-secret", "http://127.0.0.1:" + port);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void tokenIsCachedAcrossSends() throws Exception {
        WeChatClient client = startClient();
        client.sendText("openid-1", "你好");
        client.sendText("openid-1", "第二条");
        assertEquals(2, sendCalls.get());
        assertEquals(1, tokenCalls.get(), "token 未过期时复用缓存");
        assertTrue(sendBodies.get(0).contains("openid-1"));
        assertTrue(sendBodies.get(1).contains("第二条"));
    }

    @Test
    void expiredTokenTriggersRefreshAndRetry() throws Exception {
        sendErrcodes.addAll(List.of(40001, 0));
        WeChatClient client = startClient();
        client.sendText("openid-2", "重试场景");
        assertEquals(2, sendCalls.get(), "首次 40001 → 刷新 token 后重试");
        assertEquals(2, tokenCalls.get(), "重试前应强刷 access_token");
        assertTrue(sendBodies.get(1).contains("重试场景"));
    }

    @Test
    void persistentSendFailureDoesNotThrow() throws Exception {
        sendErrcodes.addAll(List.of(500, 500));
        WeChatClient client = startClient();
        client.sendText("openid-3", "持续失败");
        assertEquals(1, sendCalls.get(), "errcode 500（非 token 失效）不重试，也不向调用方抛出");
    }
}
