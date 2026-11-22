package com.travel.agent.llm;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则兜底实现：未配置 API Key 时系统仍然完整可用，
 * 所有 Agent 环节都有模板化降级输出。
 */
@Component
public class RuleBasedLlmClient implements LlmClient {

    @Override
    public String chat(List<LlmMessage> messages, double temperature) {
        return null;
    }

    @Override
    public boolean available() {
        return false;
    }
}
