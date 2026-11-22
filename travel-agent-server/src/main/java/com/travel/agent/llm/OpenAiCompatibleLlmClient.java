package com.travel.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.observability.TravelMetrics;
import com.travel.agent.util.ErrorSummary;
import com.travel.agent.util.ExternalDegradationMetrics;
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
    /** T5 业务指标：travel.llm.tokens / travel.llm.cost.usd（token 用量解析见 {@link #recordUsage}） */
    private final TravelMetrics travelMetrics;

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
                ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_LLM,
                        ExternalDegradationMetrics.REASON_BAD_RESPONSE);
                return null;
            }
            // T5：token 用量在内容校验之前解析——回复为空/被截断的调用，供应商同样计费
            recordUsage(resp);
            String content = resp.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                meterRegistry.counter("llm.calls", "result", "failed").increment();
                ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_LLM,
                        ExternalDegradationMetrics.REASON_BAD_RESPONSE);
                return null;
            }
            meterRegistry.counter("llm.calls", "result", "ok").increment();
            return content;
        } catch (Exception e) {
            // 失败即降级为规则引擎：这里只记指标与日志，不向上抛。
            // P3：记录根因类型（ResourceAccessException -> SocketTimeoutException / ConnectException），
            // 否则日志里只剩包装异常的一句话，排障时分不清是超时还是连不上
            meterRegistry.counter("llm.calls", "result", "failed").increment();
            ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_LLM, e);
            log.warn("LLM 调用失败，降级为规则模板: {}", ErrorSummary.describe(e));
            return null;
        } finally {
            meterRegistry.timer("llm.latency").record(java.time.Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    /**
     * 解析响应 {@code usage} 并记录 token 与估算成本（T5）。
     *
     * <p>没有 usage 就什么都不记——不按字符数估算，宁可少一个点，不能往成本面板写猜测值。
     * 兼容 OpenAI Chat Completions 的 {@code prompt_tokens/completion_tokens} 与
     * 少数兼容实现的 {@code input_tokens/output_tokens}。
     *
     * <p>观测代码绝不能把一次已经成功的调用变失败：任何异常都只落 debug。
     */
    private void recordUsage(JsonNode resp) {
        try {
            JsonNode usage = resp.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return;
            }
            long prompt = usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(0));
            long completion = usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(0));
            if (prompt <= 0 && completion <= 0) {
                return;
            }
            travelMetrics.recordLlmUsage(prompt, completion);
        } catch (RuntimeException e) {
            log.debug("解析 LLM usage 失败: {}", e.getMessage());
        }
    }

    @Override
    public boolean available() {
        return props.usable();
    }
}
