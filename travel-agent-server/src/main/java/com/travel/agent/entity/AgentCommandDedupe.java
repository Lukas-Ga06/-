package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Agent 写入类命令的幂等台账（P0-01）。
 *
 * <p>一条记录 = 一次 {@code (user_id, request_id)} 的写入命令。客户端为每次命令生成稳定
 * requestId，重试时复用；服务端据此保证「同一命令无论刷新、重连、重放都只写一次」。
 *
 * <p>行状态只有两种：{@link #STATUS_IN_PROGRESS}（已抢占、执行中）与
 * {@link #STATUS_COMPLETED}（已完成、{@code resultJson} 可原样重放）。执行失败时行会被删除，
 * 让用户能用同一个 requestId 重试 —— 幂等台账不能变成「一次失败永久拉黑」。
 */
@Entity
@Table(name = "agent_command_dedupe")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentCommandDedupe {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** 触发写入的意图（RECORD_EXPENSE / ADD_MEMO / ADD_SCHEDULE），便于排查 */
    @Column(length = 32)
    private String intent;

    @Column(nullable = false, length = 16)
    private String status;

    /** 首次执行结果（JSON）：重放时原样返回，用户看到的仍是「那一次」的答复 */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}