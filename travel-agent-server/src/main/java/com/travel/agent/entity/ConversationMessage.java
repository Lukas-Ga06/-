package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 对话消息：Agent 会话记忆的最小存储。
 *
 * 只存「谁在哪个会话里说了什么」，供后续轮次的意图识别做指代消解
 * （「那把第二天的酒店也换掉」需要知道上一轮聊的是什么）。
 * 内容做截断存储，避免把超长粘贴文本原样入库。
 */
@Entity
@Table(name = "conversation_message", indexes = {
        @Index(name = "idx_conv_user_session", columnList = "user_id, conversation_id"),
        @Index(name = "idx_conv_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /** 单条入库上限：超出截断（历史只用于意图理解，不需要全文） */
    public static final int MAX_CONTENT_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 该轮识别出的意图（用户消息才有），便于排查意图路由问题 */
    @Column(length = 32)
    private String intent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
