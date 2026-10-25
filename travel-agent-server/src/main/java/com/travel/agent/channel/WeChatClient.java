package com.travel.agent.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 微信公众号 API 客户端：access_token 管理（7200s 有效，过期自动刷新）+ 客服消息发送。
 * 仅在配置了 appid/secret 时使用——把回复从「被动 5s 窗口」解耦为异步发送。
 */
@Slf4j
public class WeChatClient {

    private static final long TOKEN_TTL_MILLIS = 90 * 60 * 1000L; // 官方 7200s，提前 30 分钟刷新

    private final String appid;
    private final String secret;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong tokenFetchedAt = new AtomicLong(0);
    private volatile String accessToken;

    public WeChatClient(String appid, String secret) {
        this(appid, secret, "https://api.weixin.qq.com");
    }

    /** 测试用：可指向 stub 服务器 */
    WeChatClient(String appid, String secret, String baseUrl) {
        this.appid = appid;
        this.secret = secret;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** 发送客服文本消息；token 失效自动强刷一次重试。失败只记日志（结果无处投递） */
    public void sendText(String openid, String text) {
        try {
            postSend(openid, text, false);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("40001") || message.contains("42001")) {
                log.info("微信 access_token 失效，强刷后重试一次");
                tokenFetchedAt.set(0);
                try {
                    postSend(openid, text, true);
                    return;
                } catch (Exception retryError) {
                    log.warn("微信客服消息重试仍失败 openid={}: {}", openid, retryError.getMessage());
                    return;
                }
            }
            log.warn("微信客服消息发送失败 openid={}: {}", openid, message);
        }
    }

    private void postSend(String openid, String text, boolean forceRefresh) throws Exception {
        String body = restClient.post()
                .uri("/cgi-bin/message/custom/send?access_token={token}", token(forceRefresh))
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of(
                        "touser", openid,
                        "msgtype", "text",
                        "text", Map.of("content", text))))
                .retrieve()
                .body(String.class);
        JsonNode resp = mapper.readTree(body == null ? "{}" : body);
        if (resp.path("errcode").asInt(0) != 0) {
            throw new IllegalStateException("errcode=" + resp.path("errcode").asInt()
                    + " " + resp.path("errmsg").asText());
        }
    }

    private synchronized String token(boolean forceRefresh) throws Exception {
        long now = System.currentTimeMillis();
        if (!forceRefresh && accessToken != null && now - tokenFetchedAt.get() < TOKEN_TTL_MILLIS) {
            return accessToken;
        }
        String body = restClient.get()
                .uri("/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}", appid, secret)
                .retrieve()
                .body(String.class);
        JsonNode resp = mapper.readTree(body == null ? "{}" : body);
        String token = resp.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("获取 access_token 失败: " + body);
        }
        accessToken = token;
        tokenFetchedAt.set(now);
        return token;
    }
}
