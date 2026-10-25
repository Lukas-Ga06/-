package com.travel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP streamable HTTP 传输：POST JSON-RPC 到单一端点。
 * 响应可能是 application/json，也可能是 text/event-stream（逐个 data: 行里找与本请求 id 相同的那条）。
 * initialize 响应里若带 Mcp-Session-Id，后续请求原样携带。
 */
@Slf4j
public class HttpMcpTransport implements McpTransport {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong idSeq = new AtomicLong();
    private volatile String sessionId;

    public HttpMcpTransport(String url, long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout((int) Math.max(timeoutMs, 5_000));
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(factory)
                .build();
    }

    @Override
    public JsonNode request(JsonNode requestBody) throws Exception {
        long id = idSeq.incrementAndGet();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestBody).put("id", id)
                .put("jsonrpc", "2.0");
        ResponseEntity<byte[]> response = restClient.post()
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
                    if (sessionId != null) {
                        headers.set(SESSION_HEADER, sessionId);
                    }
                })
                .body(requestBody.toString())
                .retrieve()
                .toEntity(byte[].class);
        String header = response.getHeaders().getFirst(SESSION_HEADER);
        if (header != null && !header.isBlank()) {
            sessionId = header;
        }
        MediaType contentType = response.getHeaders().getContentType();
        byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
        if (contentType != null && contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            return parseSse(body, id);
        }
        if (body.length == 0) {
            throw new IOException("MCP 响应为空 (HTTP " + response.getStatusCode().value() + ")");
        }
        JsonNode node = mapper.readTree(new ByteArrayInputStream(body));
        if (!node.has("id")) {
            // 收到的是通知而非响应（部分宽松实现），当作成功但不携带结果
            return mapper.createObjectNode();
        }
        return node;
    }

    /** SSE 响应：data: 行逐条解析，返回 id 匹配的那条 JSON-RPC 响应 */
    private JsonNode parseSse(byte[] body, long id) throws IOException {
        String text = new String(body, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(payload);
                if (node.has("id") && node.path("id").asLong(-1) == id) {
                    return node;
                }
            } catch (IOException ignored) {
                // 非本请求的注释行/心跳，跳过
            }
        }
        throw new IOException("MCP SSE 响应中未找到 id=" + id + " 的结果");
    }

    @Override
    public void notify(JsonNode notification) throws Exception {
        ((com.fasterxml.jackson.databind.node.ObjectNode) notification).put("jsonrpc", "2.0");
        restClient.post()
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (sessionId != null) {
                        headers.set(SESSION_HEADER, sessionId);
                    }
                })
                .body(notification.toString())
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void close() {
        // HTTP 无持久连接需要清理
    }
}
