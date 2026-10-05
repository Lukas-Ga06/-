package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 学习产物：知识框架 / 学习路线 / 重点归纳 */
@Entity
@Table(name = "learning_artifact", indexes = {
        @Index(name = "idx_artifact_user_kind", columnList = "user_id,kind")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningArtifact {

    public static final String KIND_FRAMEWORK = "FRAMEWORK";
    public static final String KIND_PATH = "PATH";
    public static final String KIND_SUMMARY = "SUMMARY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 20, nullable = false)
    private String kind;

    @Column(length = 200, nullable = false)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
