package com.travel.agent.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Embedding：成功返回归一化向量；失败后进程内锁定降级到本地哈希（向量空间一致） */
class OpenAiCompatibleEmbeddingServiceTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private boolean failAll = false;

    private OpenAiCompatibleEmbeddingService service(int port) {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://127.0.0.1:" + port + "/v1");
        props.setApiKey("test-key");
        return new OpenAiCompatibleEmbeddingService(props, new LocalHashEmbeddingService());
    }

    private HttpServer startServer() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/v1/embeddings", exchange -> {
            calls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (failAll) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            StringBuilder arr = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0) arr.append(',');
                arr.append(i + 1).append(".0");
            }
            byte[] resp = ("{\"data\":[{\"embedding\":[" + arr + "]}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        http.start();
        return http;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedsAndNormalizes() throws IOException {
        server = startServer();
        OpenAiCompatibleEmbeddingService service = service(server.getAddress().getPort());
        float[] v = service.embed("故宫攻略");
        assertEquals(8, v.length);
        assertEquals(8, service.dimension());
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        assertTrue(Math.abs(norm - 1.0) < 1e-5, "向量应 L2 归一化");
    }

    @Test
    void degradesPermanentlyOnFailure() throws IOException {
        server = startServer();
        OpenAiCompatibleEmbeddingService service = service(server.getAddress().getPort());
        failAll = true;
        float[] degraded = service.embed("故宫攻略");
        assertEquals(256, degraded.length, "失败后立即回落本地哈希（256 维）");
        assertEquals(256, service.dimension());
        failAll = false;
        float[] again = service.embed("再试一次");
        assertEquals(256, again.length, "降级锁定：恢复后本进程仍用哈希，保持向量空间一致");
        int callsAfterLock = calls.get();
        service.embed("第三次");
        assertEquals(callsAfterLock, calls.get(), "锁定后不再请求真实端点");
        assertNotEquals(0, degraded.length);
    }
}
