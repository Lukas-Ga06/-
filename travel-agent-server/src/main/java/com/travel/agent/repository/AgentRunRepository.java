package com.travel.agent.repository;

import com.travel.agent.entity.AgentRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    Optional<AgentRun> findByRunId(String runId);

    Optional<AgentRun> findByRunIdAndUserId(String runId, Long userId);

    /** 用户的最近运行（按 id 倒序，等价于时间倒序），用 Pageable 限制条数 */
    List<AgentRun> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 追踪保留期清理：删除阈值之前的运行（任务/工具追踪由服务层按 run_id 级联清） */
    long deleteByStartedAtBefore(LocalDateTime threshold);
}
