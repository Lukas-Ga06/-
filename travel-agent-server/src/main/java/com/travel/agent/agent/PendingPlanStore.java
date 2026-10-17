package com.travel.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.PendingPlanRecord;
import com.travel.agent.repository.PendingPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 待确认计划存储（落库实现）。
 *
 * 之前是进程内 Map：多实例部署时，确认请求被负载均衡打到另一台机器就会失败，
 * 重启也会丢掉用户还没点的确认。改为 JPA 存储后这些都不再是问题，
 * 代价是需要序列化 AgentPlan（用应用统一的 ObjectMapper）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingPlanStore {

    /** 待确认方案有效期（分钟） */
    public static final long TTL_MINUTES = 30;

    public record PendingPlan(String planId, Long userId, Long tripId,
                              AgentPlan plan, AgentDtos.AgentResult preview,
                              String planRevision, Instant createdAt) {
    }

    private final PendingPlanRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void put(PendingPlan pending) {
        try {
            repository.save(PendingPlanRecord.builder()
                    .planId(pending.planId())
                    .userId(pending.userId())
                    .tripId(pending.tripId())
                    .planJson(objectMapper.writeValueAsString(pending.plan()))
                    .previewJson(objectMapper.writeValueAsString(pending.preview()))
                    .planRevision(pending.planRevision())
                    .createdAt(LocalDateTime.ofInstant(pending.createdAt(), ZoneId.systemDefault()))
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("待确认方案序列化失败", e);
        }
        purgeExpired();
    }

    /** 取出并删除（校验归属与有效期） */
    @Transactional
    public PendingPlan take(String planId, Long userId) {
        PendingPlan found = peek(planId, userId);
        if (found != null) {
            repository.deleteById(planId);
        }
        return found;
    }

    /** 查看待确认计划：过期即删除并返回 null */
    public PendingPlan peek(String planId, Long userId) {
        if (planId == null) {
            return null;
        }
        PendingPlanRecord record = repository.findById(planId).orElse(null);
        if (record == null) {
            return null;
        }
        if (record.getCreatedAt().plusMinutes(TTL_MINUTES).isBefore(LocalDateTime.now())) {
            repository.deleteById(planId);
            return null;
        }
        if (!record.getUserId().equals(userId)) {
            return null;
        }
        return toPending(record);
    }

    @Transactional
    public void remove(String planId) {
        if (planId != null) {
            repository.deleteById(planId);
        }
    }

    /** 清理过期方案，避免表无限增长 */
    @Transactional
    public int purgeExpired() {
        long removed = repository.deleteByCreatedAtBefore(LocalDateTime.now().minusMinutes(TTL_MINUTES));
        if (removed > 0) {
            log.debug("清理过期待确认方案 {} 条", removed);
        }
        return (int) removed;
    }

    private PendingPlan toPending(PendingPlanRecord record) {
        try {
            return new PendingPlan(
                    record.getPlanId(),
                    record.getUserId(),
                    record.getTripId(),
                    objectMapper.readValue(record.getPlanJson(), AgentPlan.class),
                    objectMapper.readValue(record.getPreviewJson(), AgentDtos.AgentResult.class),
                    record.getPlanRevision(),
                    record.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            // 反序列化失败说明这条记录已经不可用，直接清掉，避免反复报错
            log.error("待确认方案反序列化失败，已清理: planId={}", record.getPlanId(), e);
            repository.deleteById(record.getPlanId());
            return null;
        }
    }
}
