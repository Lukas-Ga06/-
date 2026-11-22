package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "rag_chunk", indexes = {
        @Index(name = "idx_rag_chunk_document", columnList = "document_id"),
        @Index(name = "idx_rag_chunk_version", columnList = "version_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /**
     * 所属文档版本（{@link RagDocumentVersion#getId()}）。
     * <p>NULL = 版本化上线前的历史数据：按契约仍参与检索（兼容规则）；
     * 非 NULL 时只有对应版本为 ACTIVE 才参与检索。版本切换（内容更新/回滚）不再删除旧块，
     * 旧版本的块保留在库里用于审计与回滚。
     */
    @Column(name = "version_id")
    private Long versionId;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** 向量以 JSON 数组字符串存储，例如 "[0.12,0.33,...]"。生产可切换 Pgvector。 */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "dim")
    private Integer dim;
}