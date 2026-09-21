package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "rag_chunk")
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

    @Column(columnDefinition = "TEXT")
    private String content;

    /** 向量以 JSON 数组字符串存储，例如 "[0.12,0.33,...]"。生产可切换 Pgvector。 */
    @Column(columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "dim")
    private Integer dim;
}
