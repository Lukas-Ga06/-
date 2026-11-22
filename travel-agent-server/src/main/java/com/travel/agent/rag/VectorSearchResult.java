package com.travel.agent.rag;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 向量检索结果 */
@Data
@AllArgsConstructor
public class VectorSearchResult {
    private RagChunk chunk;
    private RagDocument document;
    private double score;
}
