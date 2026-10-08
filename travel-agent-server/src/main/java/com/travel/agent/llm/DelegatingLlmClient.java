package com.travel.agent.llm;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/** 委托客户端：优先真实模型，失败或未启用时自动降级 */
@Component
@Primary
@Slf4j
@RequiredArgsConstructor
public class DelegatingLlmClient implements LlmClient {

    private final OpenAiCompatibleLlmClient openAiClient;
    private final LlmProperties props;
    /** 所有调用方都注入 LlmClient（拿到的是这个委托类），因此"跳过"计数放在这里 */
    private final MeterRegistry meterRegistry;

    @Override
    public String chat(List<LlmMessage> messages, double temperature) {
        if (!openAiClient.available()) {
            // 未配置 Key / 显式关闭：整条链路走规则引擎，这里记一笔便于在
            // /actuator/metrics/llm.calls 上直接看出"模型没在跑"
            meterRegistry.counter("llm.calls", "result", "skipped").increment();
            return null;
        }
        String result = openAiClient.chat(messages, temperature);
        if (result != null && !result.isBlank()) {
            return result;
        }
        return null;
    }

    @Override
    public boolean available() {
        return openAiClient.available();
    }
}
