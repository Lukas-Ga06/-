package com.travel.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** OpenAI-compatible 实现（OpenAI / DeepSeek / Qwen / Ollama 等均兼容） */
@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties props;

    private RestClient client;

    private synchronized RestClient client() {
        if (client == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(props.getConnectTimeoutMs());
            factory.setReadTimeout(props.getReadTimeoutMs());
            client = RestClient.builder()
                    .baseUrl(props.getBaseUrl())
                    .requestFactory(factory)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        }
        return client;
    }

    @Override
    public String chat(List<LlmMessage> messages, double temperature) {
        if (!props.usable()) {
            return null;
        }
        try {
            List<Map<String, String>> msgs = messages.stream()
                    .map(m -> Map.of("role", m.role(), "content", m.content()))
                    .toList();
            JsonNode resp = client().post()
                    .uri("/chat/completions")
                    .body(Map.of("model", props.getModel(), "temperature", temperature, "messages", msgs))
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) {
                return null;
            }
            return resp.path("choices").path(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("LLM 调用失败，降级为规则模板: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean available() {
        return props.usable();
    }
}
