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
}
