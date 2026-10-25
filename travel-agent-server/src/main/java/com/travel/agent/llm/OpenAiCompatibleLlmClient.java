package com.travel.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** OpenAI-compatible 实现（OpenAI / DeepSeek / Qwen / Ollama 等均兼容） */
@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties props;
    /** 指标：llm.calls{result=ok|failed|skipped}、llm.latency —— 可在 /actuator/metrics 查询 */
    private final MeterRegistry meterRegistry;

    /** 默认超时的客户端复用；带自定义读超时的调用按超时值各缓存一个 */
    private RestClient client;
    private final Map<Integer, RestClient> timeoutClients = new ConcurrentHashMap<>();

    private synchronized RestClient client() {
        if (client == null) {
            client = buildClient(props.getReadTimeoutMs());
        }
        return client;
    }

    private RestClient buildClient(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String chat(List<LlmMessage> messages, double temperature) {
        if (!props.usable()) {
            // "跳过"由 DelegatingLlmClient 统计，这里只负责真实调用的成功/失败
            return null;
        }
        return doChat(messages, temperature, client());
    }

    @Override
    public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
        if (!props.usable()) {
            return null;
        }
        RestClient timed = timeoutClients.computeIfAbsent(readTimeoutMs, k -> buildClient(k));
        return doChat(messages, temperature, timed);
    }

    private String doChat(List<LlmMessage> messages, double temperature, RestClient target) {
        long startedAt = System.nanoTime();
        try {
            List<Map<String, String>> msgs = messages.stream()
                    .map(m -> Map.of("role", m.role(), "content", m.content()))
                    .toList();
            JsonNode resp = target.post()
                    .uri("/chat/completions")
                    .body(Map.of("model", props.getModel(), "temperature", temperature, "messages", msgs))
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null) {
                meterRegistry.counter("llm.calls", "result", "failed").increment();
                return null;
            }
            String content = resp.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                meterRegistry.counter("llm.calls", "result", "failed").increment();
                return null;
            }
            meterRegistry.counter("llm.calls", "result", "ok").increment();
            return content;
        } catch (Exception e) {
            // 失败即降级为规则引擎：这里只记指标与日志，不向上抛
            meterRegistry.counter("llm.calls", "result", "failed").increment();
            log.warn("LLM 调用失败，降级为规则模板: {}", e.getMessage());
            return null;
        } finally {
            meterRegistry.timer("llm.latency").record(java.time.Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    @Override
    public boolean available() {
        return props.usable();
    }
}
