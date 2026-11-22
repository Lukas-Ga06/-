package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.AgentCommandDedupe;
import com.travel.agent.repository.AgentCommandDedupeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 写入类 Agent 命令的幂等执行闸门（P0-01）。
 *
 * <p>问题：SSE 走 GET + EventSource，用户消息在 URL 里；刷新、前进后退、弱网重连都可能把
 * 「记一下明天办签证」重放一次，于是备忘/日程/账单被重复创建。前端已在发送前消费 URL 参数，
 * 但客户端自律不算保证 —— 这里按 {@code (userId, requestId)} 在服务端做「最多一次」。
 *
 * <p>协议（三步，由调用方负责顺序）：
 * <ol>
 *   <li>{@link #claim} 抢占：首次 = {@link Decision#EXECUTE}；已完成 = {@link Decision#REPLAY}
 *       （原样返回首次结果，不再执行）；并发同 requestId = {@link Decision#IN_PROGRESS}；</li>
 *   <li>执行写入；</li>
 *   <li>{@link #complete} 记下结果供重放，或 {@link #release} 释放让用户可重试。</li>
 * </ol>
 *
 * <p>失败必须 fail-open：执行抛异常就删掉抢占行，否则同一条命令会被永久拉黑，
 * 用户重试永远拿到「正在处理中」。台账只负责去重，不负责保存业务数据。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCommandIdempotency {

    /** 抢占结论 */
    public enum Decision {
        /** 首次执行 */
        EXECUTE,
        /** 已有完成结果，直接重放，绝不能再写一次 */
        REPLAY,
        /** 同一 requestId 的另一个请求正在执行 */
        IN_PROGRESS
    }

    /**
     * 一次抢占的结果。
     *
     * @param decision 抢占结论
     * @param result   REPLAY / IN_PROGRESS 时给用户的答复；EXECUTE 时为 null
     */
    public record Outcome(Decision decision, AgentDtos.AgentResult result) {

        static Outcome execute() {
            return new Outcome(Decision.EXECUTE, null);
        }

        static Outcome replay(AgentDtos.AgentResult result) {
            return new Outcome(Decision.REPLAY, result);
        }

        static Outcome inProgress(AgentDtos.AgentResult result) {
            return new Outcome(Decision.IN_PROGRESS, result);
        }
    }

    private final AgentCommandDedupeRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 抢占一次写入命令。requestId 为空时不做幂等（调用方不关心去重，行为与升级前一致）。
     */
    public Outcome claim(Long userId, String requestId, String intent) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return Outcome.execute();
        }
        Optional<AgentCommandDedupe> existing = repository.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) {
            return decide(existing.get());
        }
        AgentCommandDedupe row = AgentCommandDedupe.builder()
                .userId(userId)
                .requestId(requestId)
                .intent(intent)
                .status(AgentCommandDedupe.STATUS_IN_PROGRESS)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        try {
            // saveAndFlush 立刻把抢占行落到唯一键上：并发同 requestId 的第二个请求会撞唯一键
            repository.saveAndFlush(row);
            return Outcome.execute();
        } catch (DataIntegrityViolationException e) {
            // 并发抢占失败：以先落库那一行为准，绝不重复执行
            log.info("同 requestId 并发抢占，按已存在记录处理: user={} requestId={}", userId, requestId);
            return repository.findByUserIdAndRequestId(userId, requestId)
                    .map(this::decide)
                    .orElseGet(Outcome::execute);
        }
    }

    /** 记录首次结果，供后续重放（失败只记日志：业务写入已成功，不能因为台账失败而回滚） */
    public void complete(Long userId, String requestId, AgentDtos.AgentResult result) {
        if (userId == null || requestId == null || requestId.isBlank() || result == null) {
            return;
        }
        repository.findByUserIdAndRequestId(userId, requestId).ifPresent(row -> {
            row.setStatus(AgentCommandDedupe.STATUS_COMPLETED);
            row.setResultJson(serialize(result));
            row.setUpdatedAt(LocalDateTime.now());
            try {
                repository.save(row);
            } catch (RuntimeException e) {
                log.warn("幂等台账写入失败（不影响已完成的业务写入）: {}", e.getMessage());
            }
        });
    }

    /** 执行失败：释放抢占，允许用同一 requestId 重试 */
    public void release(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return;
        }
        repository.findByUserIdAndRequestId(userId, requestId).ifPresent(row -> {
            if (!AgentCommandDedupe.STATUS_COMPLETED.equals(row.getStatus())) {
                try {
                    repository.delete(row);
                } catch (RuntimeException e) {
                    log.warn("幂等台账释放失败: {}", e.getMessage());
                }
            }
        });
    }

    private Outcome decide(AgentCommandDedupe row) {
        if (AgentCommandDedupe.STATUS_COMPLETED.equals(row.getStatus())) {
            AgentDtos.AgentResult stored = deserialize(row.getResultJson());
            if (stored != null) {
                return Outcome.replay(stored);
            }
            // 结果读不出来也不能再执行一次：宁可给一条「已执行过」的答复，也不制造重复写入
            return Outcome.replay(AgentDtos.AgentResult.builder()
                    .intent(row.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("这条指令已经执行过了，无需重复提交。")
                    .build());
        }
        return Outcome.inProgress(AgentDtos.AgentResult.builder()
                .intent(row.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("这条指令正在处理中，请稍候查看结果。")
                .build());
    }

    private String serialize(AgentDtos.AgentResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("幂等结果序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private AgentDtos.AgentResult deserialize(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AgentDtos.AgentResult.class);
        } catch (Exception e) {
            log.warn("幂等结果反序列化失败: {}", e.getMessage());
            return null;
        }
    }
}