package com.travel.agent.repository;

import com.travel.agent.entity.AgentRunStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentRunStageRepository extends JpaRepository<AgentRunStage, Long> {

    Optional<AgentRunStage> findTopByRunIdOrderBySeqNoDesc(String runId);

    List<AgentRunStage> findByRunIdOrderBySeqNoAsc(String runId);

    /** 追踪保留期清理：按阶段进入时间删除（与 run 保留期同一阈值） */
    long deleteByEnteredAtBefore(LocalDateTime threshold);
}
