package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 会话元数据（V23）：目前只承载「用户显式改的名字」。
 *
 * <p>为什么单独一张表：标题原本是从「该会话首条用户消息」推导出来的，没有地方保存改名。
 * 放进 {@code conversation_message} 会让每条消息都带一份冗余标题，且改一次名要写全部行。
 *
 * <p>没有行 = 没改过名 = 继续用推导标题。删除会话时与消息一起删。
 * 与 {@code conversation_message} 一样，一切查询都以 {@code user_id} 隔离，
 * {@code conversation_id} 只是同一用户名下的分组键，<b>不参与权限判断</b>。
 */
@Entity
@Table(name = "conversation_meta",
        uniqueConstraints = @UniqueConstraint(name = "uk_conv_meta_user_conv",
                columnNames = {"user_id", "conversation_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    /**
     * 用户改的名字（与推导标题同一套截断规则，最长 {@code TITLE_MAX_LENGTH + 1} 带省略号，
     * 列宽留出余量）；null = 没改过。
     */
    @Column(length = 64)
    private String title;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}