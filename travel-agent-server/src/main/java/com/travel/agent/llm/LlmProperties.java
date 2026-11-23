package com.travel.agent.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

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
    /**
     * 启动后是否异步探测 {@code GET {baseUrl}/models}（P0-A）：默认开启。
     * 关闭后状态页的 llmModelAvailable 保持 null（未校验），排障/无外网环境可显式关掉。
     */
    private boolean modelProbeEnabled = true;
    /** 每用户每日 LLM 调用次数上限；0 = 不限制（UserContext 取不到用户的系统调用不受限） */
    private int quotaDailyLimit = 0;

    // ==================== 成本估算（可观测性，T5） ====================
    // 默认 0 = 不计成本：不同供应商/模型价目表差异极大，写死一个"看起来合理"的默认值
    // 会让成本面板长期虚报。真正需要成本 SLO 时，按当期价目表配置这两个值（美元 / 1K tokens）。
    /** 每 1K prompt（输入）token 的美元单价；默认 0 = 不记录 travel.llm.cost.usd */
    private double promptPricePer1kUsd = 0.0;
    /** 每 1K completion（输出）token 的美元单价；默认 0 = 不记录 travel.llm.cost.usd */
    private double completionPricePer1kUsd = 0.0;

    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * base-url 的 host（不含协议、路径、查询串与 Key），状态页用它展示 provider。
     * 解析失败返回 null——绝不把完整地址（可能带内网/凭据信息）暴露出去。
     */
    public String providerHost() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
