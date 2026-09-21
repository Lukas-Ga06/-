package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 待确认计划存储（MVP 内存实现，多实例部署时替换为 Redis） */
@Component
public class PendingPlanStore {

    private static final long TTL_MS = 30 * 60 * 1000L;

    public record PendingPlan(String planId, Long userId, Long tripId,
                              AgentPlan plan, AgentDtos.AgentResult preview, Instant createdAt) {}

    private final Map<String, PendingPlan> store = new ConcurrentHashMap<>();

    public void put(PendingPlan pending) {
        store.put(pending.planId(), pending);
    }

    /** 取出并删除（校验归属与有效期） */
    public PendingPlan take(String planId, Long userId) {
        PendingPlan pending = store.remove(planId);
        if (pending == null) {
            return null;
        }
        if (!pending.userId().equals(userId)) {
            store.put(planId, pending); // 归属不符，放回
            return null;
        }
        if (pending.createdAt().plusMillis(TTL_MS).isBefore(Instant.now())) {
            return null;
        }
        return pending;
    }

    public PendingPlan peek(String planId, Long userId) {
        PendingPlan pending = store.get(planId);
        if (pending == null || !pending.userId().equals(userId)) {
            return null;
        }
        return pending;
    }

    public void remove(String planId) {
        store.remove(planId);
    }
}
