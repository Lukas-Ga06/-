package com.travel.agent.repository;

import com.travel.agent.entity.AgentRunEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentRunEventRepository extends JpaRepository<AgentRunEvent, Long> {

    /** 断线续传：补发 seq 严格大于游标的事件，按 seq 升序（游标语义 = 客户端已确认收到） */
    List<AgentRunEvent> findByRunIdAndSeqNoGreaterThanOrderBySeqNoAsc(String runId, Integer seqNo);

    /** 同上但限制单批数量：运行中轮询避免一次拉出整段历史 */
    List<AgentRunEvent> findByRunIdAndSeqNoGreaterThanOrderBySeqNoAsc(
            String runId, Integer seqNo, Pageable pageable);

    List<AgentRunEvent> findByRunIdOrderBySeqNoAsc(String runId);

    Optional<AgentRunEvent> findTopByRunIdOrderBySeqNoDesc(String runId);

    /** 追踪保留期清理：按事件写入时间删除（与 run 保留期同一阈值） */
    long deleteByCreatedAtBefore(LocalDateTime threshold);

    /** 按 run 清理（单 run 重跑前的显式重置；正常路径走保留期清理） */
    long deleteByRunId(String runId);
}
