package com.travel.agent.repository;

import com.travel.agent.entity.ToolCallTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ToolCallTraceRepository extends JpaRepository<ToolCallTrace, Long> {

    /** 一个 run 内的全部工具调用（按发生顺序） */
    List<ToolCallTrace> findByRunIdOrderByIdAsc(String runId);

    /** 追踪保留期清理 */
    long deleteByStartedAtBefore(LocalDateTime threshold);
}
