package com.travel.agent.rag;

import java.util.List;

/** 向量库接口：MVP 为内存实现，生产可替换为 Pgvector/Milvus */
public interface VectorStore {

    /**
     * 重建索引。版本化（batch9 / C）后只装载「version_id 为空的历史块」或
     * 「所属版本为 ACTIVE 的块」；内容更新/回滚后必须调用本方法切换即时生效。
     */
    void reload();

    /**
     * 检索（仅公共知识视角）：等价于 {@code search(queryVector, city, null, topK)}。
     * 保留该重载给不需要用户上下文的调用方（内部公共检索、测试）。
     */
    default List<VectorSearchResult> search(float[] queryVector, String city, int topK) {
        return search(queryVector, city, null, topK);
    }

    /**
     * 带用户上下文的检索：公共知识（userId 为空）始终可见，
     * 个人知识只在 {@code currentUserId} 与文档归属一致时可见。
     *
     * @param currentUserId 当前用户 id；null 表示只检索公共知识
     */
    List<VectorSearchResult> search(float[] queryVector, String city, Long currentUserId, int topK);
}