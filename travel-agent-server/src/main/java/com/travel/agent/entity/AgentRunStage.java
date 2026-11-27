package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 运行阶段迁移历史（P0 / V27）：每个 run 一条单调序列，回答「这个 run 依次经过哪些阶段」。
 *
 * <p>与 {@link AgentRun#getStage()} 的分工：run 表只存「当前在哪」，本表存「走过哪些」。
 * 进程崩溃后两者结合才能判断运行停在哪一步、是否已经死掉；
 * {@code (run_id, seq_no)} 唯一，seq_no 在 run 内从 1 开始。
 *
 * <p>detail 预留给未来的阶段说明（例如降级原因），当前写入为 null，避免把用户输入
 * 或内部异常细节扩散到更多表。
 */
@Entity
@Table(name = "agent_run_stage",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_run_stage",
                columnNames = {"run_id", "seq_no"}),
        indexes = @Index(name = "idx_agent_run_stage_run", columnList = "run_id, seq_no"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRunStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    /** run 内迁移序号（从 1 开始） */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** 阶段名（AgentState 名称或终态 DONE/FAILED/CANCELLED/ABANDONED） */
    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    @Column(name = "detail", length = 500)
    private String detail;
}
