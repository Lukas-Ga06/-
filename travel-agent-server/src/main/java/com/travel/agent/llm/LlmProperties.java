package com.travel.agent.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** LLM 统一配置：不把具体模型写死，换模型只改配置文件 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.llm")
public class LlmProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private double temperature = 0.4;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;

    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
