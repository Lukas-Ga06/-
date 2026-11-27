package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * SSE 事件持久化（P0 / V28）：run 内每个事件按 seq_no（从 1 开始单调递增）落库。
 *
 * <p>为什么需要它：SSE 本身易失，客户端一断线，complete 之前的全部过程与最终结果都
 * 无处可查；带 Last-Event-ID 重连时只能靠 agent_run_event 补发。事件的 SSE 帧 id 形如
 * {@code <runId>:<seq_no>}，客户端据此声明「我已经收到 seq=N 之前的所有事件」。
 *
 * <p>{@code data_json} 是事件载荷的 JSON 文本（complete 可能携带完整方案，因此用
 * LONGTEXT）；同一 run 内 seq_no 唯一（uk_agent_run_event），补发按 seq 升序、绝不重复。
 * 事件随 Agent 追踪保留期一起清理（默认 14 天）。
 */
@Entity
@Table(name = "agent_run_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_run_event",
                columnNames = {"run_id", "seq_no"}),
        indexes = @Index(name = "idx_agent_run_event_run", columnList = "run_id, seq_no"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** run 内事件序号（从 1 开始）；SSE 帧 id 的后半段 */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** SSE 事件名（start / run / intent / complete / cancelled …） */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** 事件载荷 JSON；超长时写入侧截断（续传侧按文本兜底，不会因为一条坏数据中断补发） */
    @Column(name = "data_json", columnDefinition = "LONGTEXT")
    private String dataJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
