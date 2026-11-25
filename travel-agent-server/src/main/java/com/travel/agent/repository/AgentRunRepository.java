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

    /**
     * 按 (userId, requestId) 找最近一次运行（P0）：SSE 重连/幂等重放据此回到原 run，
     * 而不是重新规划。同一 requestId 可能因失败重试产生多条，取最新一条。
     */
    Optional<AgentRun> findFirstByUserIdAndRequestIdOrderByIdDesc(Long userId, String requestId);

    /** 崩溃恢复：心跳早于阈值（心跳为空的运行由下面的 null 分支处理） */
    List<AgentRun> findByStatusAndHeartbeatAtBefore(String status, LocalDateTime threshold, Pageable pageable);

    /** 崩溃恢复：从未写过心跳的运行按开始时间判断（进程在第一次阶段推进前就崩了） */
    List<AgentRun> findByStatusAndHeartbeatAtIsNullAndStartedAtBefore(
            String status, LocalDateTime threshold, Pageable pageable);

    /** 追踪保留期清理：删除阈值之前的运行（任务/工具追踪由服务层按 run_id 级联清） */
    long deleteByStartedAtBefore(LocalDateTime threshold);
}
