package com.travel.agent.llm;

import java.util.List;

/**
 * 统一 LLM 接口。以后更换模型只修改实现类：
 * OpenAI-compatible / DeepSeek / Qwen / 本地模型均可接入。
 */
public interface LlmClient {

    /** 同步对话，失败时返回 null（由调用方走降级逻辑） */
    String chat(List<LlmMessage> messages, double temperature);

    /**
     * 带独立读超时的同步对话。
     *
     * 交互链路上的辅助调用（如方案文案润色）不能接受全局 30s 的最坏等待，
     * 用一个更紧的超时单独约束：超时同样返回 null，调用方降级到模板文案。
     */
    default String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
        return chat(messages, temperature);
    }

    /** 当前客户端是否可用（有真实模型接入） */
    boolean available();
}
