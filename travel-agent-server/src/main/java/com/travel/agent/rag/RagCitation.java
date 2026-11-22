package com.travel.agent.rag;

/**
 * RAG 检索引用（P1：可追溯的知识证据）。
 *
 * <p>每条命中都必须能回答四个问题：来自哪篇文档（{@code documentId}/{@code title}）、
 * 哪个版本（{@code version}）、哪一段（{@code chunkId}）、相似度多少（{@code score}），
 * 以及原始来源标记（{@code source}，例如内置知识库 / 用户知识库）。
 *
 * <p>{@code version} 为 null 表示版本化上线前的历史知识块（语义见 {@code RagChunk#versionId}）。
 * {@code snippet} 是命中片段原文，供方案与 SSE 事件展示，不落库。
 */
public record RagCitation(
        Long documentId,
        Integer version,
        Long chunkId,
        double score,
        String source,
        String title,
        String snippet) {

    /** 单行引用文案：前端与规则引擎降级答复共用，避免两处写法漂移 */
    public String label() {
        return "《" + (title == null ? "未命名文档" : title) + "》"
                + (version == null ? "" : " v" + version);
    }
}
