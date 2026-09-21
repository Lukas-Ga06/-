package com.travel.agent.llm;

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

    @Override
    public String chat(List<LlmMessage> messages, double temperature) {
        if (openAiClient.available()) {
            String result = openAiClient.chat(messages, temperature);
            if (result != null && !result.isBlank()) {
                return result;
            }
        }
        return null;
    }

    @Override
    public boolean available() {
        return openAiClient.available();
    }
}
