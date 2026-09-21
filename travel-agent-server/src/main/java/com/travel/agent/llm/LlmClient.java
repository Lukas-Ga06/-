package com.travel.agent.llm;

import java.util.List;

/**
 * 统一 LLM 接口。以后更换模型只修改实现类：
 * OpenAI-compatible / DeepSeek / Qwen / 本地模型均可接入。
 */
public interface LlmClient {

    /** 同步对话，失败时返回 null（由调用方走降级逻辑） */
    String chat(List<LlmMessage> messages, double temperature);

    /** 当前客户端是否可用（有真实模型接入） */
    boolean available();
}
