package com.travel.agent.repository;

import com.travel.agent.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {

    Optional<AgentTask> findByTaskId(String taskId);

    /** 一个 run 内的全部任务（按创建顺序即任务链顺序） */
    List<AgentTask> findByRunIdOrderByIdAsc(String runId);

    /** 追踪保留期清理 */
    long deleteByStartedAtBefore(LocalDateTime threshold);
}
