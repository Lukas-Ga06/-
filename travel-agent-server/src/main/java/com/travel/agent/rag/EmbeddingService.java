package com.travel.agent.rag;

/** Embedding 服务接口：生产可替换为 OpenAI / BGE 等真实模型 */
public interface EmbeddingService {

    float[] embed(String text);

    int dimension();
}
