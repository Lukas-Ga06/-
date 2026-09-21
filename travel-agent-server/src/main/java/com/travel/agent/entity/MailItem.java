package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 邮件分类结果（粘贴邮件文本 → LLM 分类 + 摘要 + 建议回复） */
@Entity
@Table(name = "mail_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 200)
    private String sender;

    @Column(length = 300)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    /** 重要 / 会议 / 账单 / 通知 / 垃圾 / 其他 */
    @Column(length = 20)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "suggested_reply", columnDefinition = "TEXT")
    private String suggestedReply;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
