package com.travel.agent.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Telegram Bot API 客户端（仅用到 getUpdates / sendMessage 两个接口）。
 * 长轮询 = 出站连接，不需要公网回调地址，本地即可运行。
 */
@Component
@Slf4j
public class TelegramClient {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelegramClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(45_000); // 长轮询挂起 + 余量
        this.restClient = RestClient.builder()
                .baseUrl(API_BASE)
                .requestFactory(factory)
                .build();
    }

    /** 拉取 offset 之后的消息（长轮询 timeout 秒）；返回空列表表示无新消息 */
    public List<JsonNode> getUpdates(String token, long offset, int timeoutSeconds) throws Exception {
        String body = restClient.get()
                .uri("/bot{token}/getUpdates?offset={offset}&timeout={timeout}&allowed_updates=%5B%22message%22%5D",
                        token, offset, timeoutSeconds)
                .retrieve()
                .body(String.class);
        JsonNode node = mapper.readTree(body == null ? "{}" : body);
        if (!node.path("ok").asBoolean(false)) {
            throw new IllegalStateException("Telegram API 返回失败: "
                    + node.path("description").asText("unknown"));
        }
        List<JsonNode> updates = new java.util.ArrayList<>();
        node.path("result").forEach(updates::add);
        return updates;
    }

    /** 发送纯文本消息；文本由调用方预先分块（Telegram 上限 4096 字符） */
    public void sendMessage(String token, long chatId, String text) {
        restClient.post()
                .uri("/bot{token}/sendMessage", token)
                .headers(headers -> headers.setContentType(MediaType.APPLICATION_JSON))
                .body(mapper.createObjectNode()
                        .put("chat_id", chatId)
                        .put("text", text)
                        .toString())
                .retrieve()
                .body(String.class);
    }
}
