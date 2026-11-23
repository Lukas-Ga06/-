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
@Table(name = "conversation_message",
        // 测试 profile 用 ddl-auto=create-drop 且关闭 Flyway，没有这条约束测试库就没有
        // 「同一轮同角色只能一条」的兜底，并发用例会「测了个寂寞」（与 V22 同一理由）。
        uniqueConstraints = @UniqueConstraint(name = "uk_conv_turn_role",
                columnNames = {"user_id", "conversation_id", "turn_seq", "role"}),
        indexes = {
                @Index(name = "idx_conv_user_session", columnList = "user_id, conversation_id"),
                @Index(name = "idx_conv_created_at", columnList = "created_at"),
                @Index(name = "idx_conv_user_scope", columnList = "user_id, scope")
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

    /**
     * 会话作用域（V21）：这段对话最近一次被使用时的页面归属，
     * 取值 {@code assistant} / {@code planner} / {@code trip:<id>}，存量数据为 null。
     *
     * <p>只是「续聊时把行程绑定恢复回来」的提示，<b>不参与任何权限判断</b>：
     * 隔离始终靠 user_id。因此不设唯一约束，也不做外键。
     */
    @Column(length = 32)
    private String scope;

    /**
     * 轮次序号（V23）：一轮问答（用户消息 + 助手回复）共用一个值，并发会话靠它成组。
     *
     * <p>分配方式见 {@code ConversationService#beginTurn}：取最大值 +1 后直接插入，
     * 撞 {@code uk_conv_turn_role} 就重试。存量数据（V23 之前的行）为 null，
     * 读取时按 {@code COALESCE(turn_seq, 0)} 排在所有新轮次之前。
     *
     * <p>不参与任何权限判断，只决定「同一会话里哪些消息属于同一轮」。
     */
    @Column(name = "turn_seq")
    private Long turnSeq;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
