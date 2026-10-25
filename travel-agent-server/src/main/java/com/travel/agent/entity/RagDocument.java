package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "rag_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String title;

    @Column(length = 100)
    private String city;

    @Column(length = 50)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 200)
    private String source;

    /** knowledge.json 内容指纹（sha256 of title+city+content）：启动同步时比对决定跳过/更新 */
    @Column(length = 64)
    private String fingerprint;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }
}
