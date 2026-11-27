package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.agent.AgentEvent;
import com.travel.agent.entity.AgentRunEvent;
import com.travel.agent.repository.AgentRunEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * SSE 事件持久化（P0 / V28）：断线续传的补发数据源。
 *
 * <p>设计约束与 {@link AgentTraceService} 一致：
 * <ul>
 *   <li><b>绝不反噬主流程</b>：写入失败只记 DEBUG，实时 SSE 照常下发；</li>
 *   <li><b>独立事务</b>：事件在发出前就已提交，客户端断线后立刻能查到；</li>
 *   <li><b>载荷截断</b>：超过约 1MB 的载荷截断并记 WARN（完整方案可能很大，
 *       但不能让单条事件撑爆 LONGTEXT / 内存）。</li>
 * </ul>
 *
 * <p>seq 由调用方（{@link AgentContext#emit}）在 run 内分配，本服务只负责落库；
 * 唯一键 {@code (run_id, seq_no)} 保证补发不会出现同 seq 的两行。
 */
@Service
@Slf4j
public class AgentRunEventService {

    /** 单条事件载荷上限（约 1MB）；超过截断并记 WARN */
    public static final int MAX_DATA_LENGTH = 1_000_000;

    /** 终态事件名：补发时看到其中之一，就不再合成 complete */
    public static final String EVENT_COMPLETE = "complete";
    public static final String EVENT_CANCELLED = "cancelled";

    /** 单批补发上限：运行中轮询取有界批次，避免一次拉出整段历史 */
    public static final int DEFAULT_BATCH_SIZE = 200;

    private static final int RUN_ID_MAX = 64;
    private static final int EVENT_TYPE_MAX = 64;

    private final AgentRunEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTx;

    public AgentRunEventService(AgentRunEventRepository eventRepository, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 追加一条事件（独立事务、失败静默）。
     *
     * <p>runId/eventType 为空或 seq &le; 0 时直接忽略：这类事件没有续传价值，
     * 且不该因为调用方的旧调用方式（不带 runId 的上下文）而写脏数据。
     */
    public void append(String runId, long seq, String eventType, Object data) {
        if (runId == null || runId.isBlank() || eventType == null || eventType.isBlank() || seq <= 0) {
            return;
        }
        String json = serialize(data, eventType);
        try {
            inNewTx(() -> eventRepository.save(AgentRunEvent.builder()
                    .runId(runId)
                    .seqNo((int) Math.min(seq, Integer.MAX_VALUE))
                    .eventType(truncate(eventType, EVENT_TYPE_MAX))
                    .dataJson(json)
                    .createdAt(LocalDateTime.now())
                    .build()));
        } catch (RuntimeException e) {
            log.debug("记录 SSE 事件失败: runId={} seq={} event={} err={}",
                    runId, seq, eventType, e.getMessage());
        }
    }

    /** 追加一条事件（AgentEngine 的 durable sink 直接用这个重载） */
    public void append(String runId, AgentEvent event) {
        if (event == null) {
            return;
        }
        append(runId, event.seq(), event.event(), event.data());
    }

    /** 补发用：seq 严格大于 {@code afterSeq} 的事件，按 seq 升序 */
    public List<AgentRunEvent> listAfter(String runId, long afterSeq) {
        if (runId == null) {
            return List.of();
        }
        return eventRepository.findByRunIdAndSeqNoGreaterThanOrderBySeqNoAsc(runId, cursor(afterSeq));
    }

    /** 补发用（有界批次）：seq 严格大于 {@code afterSeq} 的前 {@code limit} 条 */
    public List<AgentRunEvent> listAfter(String runId, long afterSeq, int limit) {
        if (runId == null) {
            return List.of();
        }
        int size = limit <= 0 ? DEFAULT_BATCH_SIZE : Math.min(limit, DEFAULT_BATCH_SIZE);
        return eventRepository.findByRunIdAndSeqNoGreaterThanOrderBySeqNoAsc(
                runId, cursor(afterSeq), PageRequest.of(0, size));
    }

    /** run 的持久化事件（测试/排障用，按 seq 升序） */
    public List<AgentRunEvent> eventsOf(String runId) {
        return runId == null ? List.of() : eventRepository.findByRunIdOrderBySeqNoAsc(runId);
    }

    /** run 内已落库的最大 seq（0 表示还没有事件） */
    public int maxSeq(String runId) {
        if (runId == null) {
            return 0;
        }
        return eventRepository.findTopByRunIdOrderBySeqNoDesc(runId)
                .map(AgentRunEvent::getSeqNo)
                .orElse(0);
    }

    /** 按 run 清理事件（保留期清理 / 单 run 重跑） */
    public long purgeByRun(String runId) {
        if (runId == null) {
            return 0;
        }
        try {
            return inNewTx(() -> eventRepository.deleteByRunId(runId));
        } catch (RuntimeException e) {
            log.debug("清理 run 事件失败: runId={} err={}", runId, e.getMessage());
            return 0;
        }
    }

    /** complete / cancelled 都算「客户端已知终态」，补发时不再合成 */
    public static boolean isTerminalEvent(String eventType) {
        return EVENT_COMPLETE.equals(eventType) || EVENT_CANCELLED.equals(eventType);
    }

    // ==================== 内部辅助 ====================

    private static int cursor(long afterSeq) {
        return (int) Math.max(0, Math.min(afterSeq, Integer.MAX_VALUE));
    }

    /**
     * 载荷序列化：失败时退化为 String（宁可损失结构也不能丢事件），超限截断并记 WARN。
     * 截断后的文本可能不是合法 JSON，续传侧会按文本兜底发送。
     */
    private String serialize(Object data, String eventType) {
        if (data == null) {
            return null;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.debug("SSE 事件载荷序列化失败，按字符串存储: event={} err={}", eventType, e.getMessage());
            json = String.valueOf(data);
        }
        if (json != null && json.length() > MAX_DATA_LENGTH) {
            log.warn("SSE 事件载荷超过 {} 字符，已截断: event={} length={}",
                    MAX_DATA_LENGTH, eventType, json.length());
            json = json.substring(0, MAX_DATA_LENGTH - 1) + "…";
        }
        return json;
    }

    private <T> T inNewTx(Supplier<T> body) {
        return newTx.execute(status -> body.get());
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
