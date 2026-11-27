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

    /**
     * 取走「最近一次调用」解析到的 token 用量（一次性消费：读后即清空）。
     *
     * <p>为什么不改 {@link #chat} 的返回值：{@code String} 是既有的、被大量调用方与测试桩
     * 依赖的契约，为了记账改签名会牵动整条链路。这里做成默认方法：
     * 真实实现（{@code OpenAiCompatibleLlmClient}）在解析响应时记录，规则实现与测试桩
     * 无需实现即返回 {@link LlmUsage#NONE}——「没有用量」是合法且诚实的默认值。
     *
     * <p>一次性语义很重要：同一个线程上连续多次调用时，读走即清空，避免把上一次的
     * 用量重复记到下一次（记账宁少不重）。
     */
    default LlmUsage takeLastUsage() {
        return LlmUsage.NONE;
    }

    /** 当前客户端是否可用（有真实模型接入） */
    boolean available();
}
