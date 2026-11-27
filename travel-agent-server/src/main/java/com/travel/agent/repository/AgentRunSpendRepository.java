package com.travel.agent.repository;

import com.travel.agent.entity.AgentRunSpend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/** 每轮 Agent 花费快照（V29）；保留期清理见 {@code AgentTraceService#purgeExpired} */
public interface AgentRunSpendRepository extends JpaRepository<AgentRunSpend, Long> {

    Optional<AgentRunSpend> findByRunId(String runId);

    long deleteByCreatedAtBefore(LocalDateTime threshold);
}