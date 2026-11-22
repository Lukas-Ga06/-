package com.travel.agent.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 真实 Embedding 配置：任意 OpenAI 兼容 /embeddings 端点（OpenAI / SiliconFlow / 智谱等） */
@Data
@Component
@ConfigurationProperties(prefix = "travel.embedding")
public class EmbeddingProperties {

    /** true 时启用真实 Embedding（@Primary 接管 RAG）；false/未配置回落本地哈希 */
    private boolean enabled = false;
    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String apiKey = "";
    private String model = "BAAI/bge-m3";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;

    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
