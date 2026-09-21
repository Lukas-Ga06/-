package com.travel.agent.rag;

import java.util.List;

/** 向量库接口：MVP 为内存实现，生产可替换为 Pgvector/Milvus */
public interface VectorStore {

    void reload();

    List<VectorSearchResult> search(float[] queryVector, String city, int topK);
}
