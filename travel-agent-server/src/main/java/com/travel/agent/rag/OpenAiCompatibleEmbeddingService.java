package com.travel.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 Embedding：OpenAI 兼容 /embeddings 端点（OpenAI / SiliconFlow / 智谱等）。
 *
 * 一致性约束：向量库里已入库的向量与查询向量必须出自同一模型。一旦真实服务调用失败，
 * 本进程**永久**降级到本地哈希 Embedding（degraded 锁定）——混用两套向量空间会让
 * 余弦相似度全部归零，检索静默失效比报错更危险。恢复需重启进程，且切换模型后要清空
 * rag_document / rag_chunk 重建知识库。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "travel.embedding", name = "enabled", havingValue = "true")
@Slf4j
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final EmbeddingProperties props;
    private final LocalHashEmbeddingService fallback;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger dimension = new AtomicInteger(-1);
    private volatile boolean degraded = false;

    public OpenAiCompatibleEmbeddingService(EmbeddingProperties props, LocalHashEmbeddingService fallback) {
        this.props = props;
        this.fallback = fallback;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.client = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (degraded || !props.usable()) {
            return fallback.embed(text);
        }
        try {
            JsonNode resp = client.post()
                    .uri("/embeddings")
                    .body(Map.of("model", props.getModel(), "input", text == null ? "" : text))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode vectorNode = resp == null ? null : resp.path("data").path(0).path("embedding");
            if (vectorNode == null || !vectorNode.isArray() || vectorNode.isEmpty()) {
                throw new IllegalStateException("embedding 响应为空");
            }
            float[] vector = new float[vectorNode.size()];
            double norm = 0;
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
                norm += vector[i] * vector[i];
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < vector.length; i++) {
                    vector[i] /= norm;
                }
            }
            dimension.set(vector.length);
            return vector;
        } catch (Exception e) {
            degraded = true;
            log.warn("真实 Embedding 调用失败，本进程降级为本地哈希（保持向量空间一致，恢复需重启）: {}", e.getMessage());
            return fallback.embed(text);
        }
    }

    @Override
    public int dimension() {
        int d = dimension.get();
        return d > 0 ? d : fallback.dimension();
    }
}
