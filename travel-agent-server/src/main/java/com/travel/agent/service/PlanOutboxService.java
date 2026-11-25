package com.travel.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.entity.PlanExecution;
import com.travel.agent.entity.PlanOutbox;
import com.travel.agent.repository.PlanOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 事务性 Outbox 的持久化与状态迁移（P0）。
 *
 * <p>职责边界：本类只负责「入队 / 条件领取 / 成功 / 失败退避 / 租约恢复」这些<b>数据库状态迁移</b>；
 * 真正执行模块的 {@link PlanExecutionService} 与调度循环 {@link PlanOutboxDispatcher} 各管一段。
 * 这样投递器的策略（批大小、间隔）可以单独改，而状态机的正确性只有一处。
 *
 * <p>退避策略：第 n 次失败后等待 {@code base * 2^(n-1)} 秒，上限 {@code max-backoff-seconds}；
 * 次数达到 {@code max-attempts} 直接置 DEAD，保留 last_error 作为证据，不再自动重试。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanOutboxService {

    /** 入队模块：与 {@link PlanExecution#MODULE_*} 同源，不在这里另起字面量 */
    public static final List<String> MODULES = List.of(
            PlanExecution.MODULE_CALENDAR, PlanExecution.MODULE_BUDGET, PlanExecution.MODULE_FOOTPRINT);

    private static final String ERROR_CLAIM_EXPIRED = "投递租约过期（进程在执行中退出）";
    private static final int LAST_ERROR_MAX = 500;

    private final PlanOutboxRepository repository;
    private final ObjectMapper objectMapper;

    /** 失败多少次后放弃（置 DEAD） */
    @Value("${travel.outbox.max-attempts:8}")
    private int maxAttempts;

    /** 指数退避基数（秒）：10s → 20s → 40s … */
    @Value("${travel.outbox.base-backoff-seconds:10}")
    private long baseBackoffSeconds;

    /** 退避上限（秒），默认 1 小时 */
    @Value("${travel.outbox.max-backoff-seconds:3600}")
    private long maxBackoffSeconds;

    /** DISPATCHING 行的领取租约（分钟）：超过则视为执行者崩溃，可被恢复重投 */
    @Value("${travel.outbox.dispatching-lease-minutes:10}")
    private long dispatchingLeaseMinutes;

    /**
     * 确认提交同事务入队：为本次方案的三个模块各写一条 PENDING 事件。
     *
     * <p>先查 {@code (plan_id, module)} 是否已存在再插入：唯一键冲突会把整个确认提交事务
     * 标记为 rollback-only（Hibernate 一旦遇到约束冲突就不再信任该事务），
     * 不能靠「catch 掉唯一键异常继续提交」来处理。
     *
     * @return 实际新入队的条数（0 表示重复提交/无行程可同步）
     */
    @Transactional
    public int enqueueAll(String planId, Long userId, Long tripId) {
        if (planId == null || userId == null) {
            return 0;
        }
        if (tripId == null) {
            // 没有行程就没有可同步的对象；不阻断确认提交，但要留下可查的痕迹
            log.warn("确认方案缺少行程标识，跳过跨模块同步入队: planId={}", sanitize(planId));
            return 0;
        }
        String payload = payloadJson(planId, tripId);
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        for (String module : MODULES) {
            if (repository.existsByPlanIdAndModule(planId, module)) {
                continue;
            }
            repository.save(PlanOutbox.builder()
                    .eventId(UUID.randomUUID().toString())
                    .planId(planId)
                    .userId(userId)
                    .tripId(tripId)
                    .module(module)
                    .payloadJson(payload)
                    .status(PlanOutbox.STATUS_PENDING)
                    .attempts(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            inserted++;
        }
        return inserted;
    }

    /** 到期待投递行（最多 limit 条） */
    @Transactional(readOnly = true)
    public List<PlanOutbox> findDue(int limit) {
        return repository.findDue(LocalDateTime.now(), PageRequest.of(0, Math.max(1, limit)));
    }

    /** 条件领取：返回 true 才是本轮唯一投递者（多实例下只有一个请求能拿到） */
    @Transactional
    public boolean claim(Long id) {
        if (id == null) {
            return false;
        }
        return repository.claim(id, LocalDateTime.now()) == 1;
    }

    /**
     * 确认请求内同步用的领取：先按 PENDING 条件领取；若该行是「上一任投递器崩溃、领取超租约」
     * 的 DISPATCHING，则接管并记一次消耗（与租约恢复同口径）。
     * 仍被人持有（未超租约）的行拿不到——保证多实例下同一模块只执行一次。
     */
    @Transactional
    public boolean claimForPlan(PlanOutbox row) {
        if (row == null || row.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (repository.claim(row.getId(), now) == 1) {
            return true;
        }
        return repository.takeoverStale(row.getId(), staleDispatchBefore(now), now, maxAttempts) == 1;
    }

    /** 投递成功：置 DONE 并清空领取时间（attempts 保留，便于观测「失败几次后成功」） */
    @Transactional
    public boolean markDone(Long id) {
        PlanOutbox row = repository.findById(id).orElse(null);
        if (row == null) {
            return false;
        }
        row.setStatus(PlanOutbox.STATUS_DONE);
        row.setClaimedAt(null);
        row.setLastError(null);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);
        return true;
    }

    /**
     * 投递失败：attempts+1、写 last_error，按指数退避排下一次；次数耗尽置 DEAD。
     *
     * @return true 表示本次失败已把该行置为 DEAD（需要人工/运维介入）
     */
    @Transactional
    public boolean markFailure(Long id, String error) {
        PlanOutbox row = repository.findById(id).orElse(null);
        if (row == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(brief(error));
        row.setClaimedAt(null);
        row.setUpdatedAt(now);
        boolean dead = attempts >= maxAttempts;
        if (dead) {
            row.setStatus(PlanOutbox.STATUS_DEAD);
            row.setNextAttemptAt(now);
            log.warn("Outbox 重试耗尽，置 DEAD: planId={} module={} attempts={}",
                    sanitize(row.getPlanId()), row.getModule(), attempts);
        } else {
            long delay = backoffSeconds(attempts);
            row.setStatus(PlanOutbox.STATUS_PENDING);
            row.setNextAttemptAt(now.plusSeconds(delay));
            log.debug("Outbox 投递失败，{}s 后重试: planId={} module={} attempts={}",
                    delay, sanitize(row.getPlanId()), row.getModule(), attempts);
        }
        repository.save(row);
        return dead;
    }

    /**
     * 崩溃恢复：把领取超过租约仍停在 DISPATCHING 的行改回可投递（attempts+1）。
     * 次数已耗尽的直接置 DEAD，避免「恢复 → 崩溃」无限循环。
     *
     * @return 本轮恢复的行数
     */
    @Transactional
    public int recoverStaleClaims() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(dispatchingLeaseMinutes);
        int revived = repository.recoverStaleClaims(staleBefore, now, maxAttempts);
        int dead = repository.deadLetterStaleClaims(staleBefore, now, ERROR_CLAIM_EXPIRED, maxAttempts);
        int recovered = revived + dead;
        if (recovered > 0) {
            log.info("Outbox 租约恢复：{} 条重新排队、{} 条重试耗尽置 DEAD", revived, dead);
        }
        return recovered;
    }

    /** 计算「早于该时刻领取的 DISPATCHING 行」视为崩溃，供同步路径接管 */
    public LocalDateTime staleDispatchBefore(LocalDateTime now) {
        return now.minusMinutes(dispatchingLeaseMinutes);
    }

    /**
     * 人工重试（POST /api/agent/executions/{id}/retry）后的 Outbox 同步：
     * 失败模块重新排队、重置退避预算；成功模块由 {@link #markDone} 收口。
     */
    @Transactional
    public boolean reviveForRetry(String planId, String module) {
        PlanOutbox row = repository.findByPlanIdAndModule(planId, module).orElse(null);
        if (row == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        row.setStatus(PlanOutbox.STATUS_PENDING);
        row.setAttempts(0);
        row.setNextAttemptAt(now);
        row.setClaimedAt(null);
        row.setLastError(null);
        row.setUpdatedAt(now);
        repository.save(row);
        return true;
    }

    /** 行程删除时的级联清理（调用方事务内执行） */
    @Transactional
    public long deleteByTripId(Long tripId) {
        if (tripId == null) {
            return 0;
        }
        return repository.deleteByTripId(tripId);
    }

    /** 退避秒数：base * 2^(attempts-1)，封顶 max；乘法前先比较，避免溢出 */
    private long backoffSeconds(int attempts) {
        long cap = Math.max(1, maxBackoffSeconds);
        long delay = Math.max(1, baseBackoffSeconds);
        for (int i = 1; i < attempts && delay < cap; i++) {
            delay *= 2;
        }
        return Math.min(delay, cap);
    }

    private String payloadJson(String planId, Long tripId) {
        try {
            return objectMapper.writeValueAsString(Map.of("planId", planId, "tripId", tripId));
        } catch (JsonProcessingException e) {
            // 载荷只用于排障，失败不影响投递（模块执行不依赖它）
            return null;
        }
    }

    private static String brief(String error) {
        if (error == null) {
            return null;
        }
        String oneLine = error.replace("\r", "_").replace("\n", "_");
        return oneLine.length() > LAST_ERROR_MAX ? oneLine.substring(0, LAST_ERROR_MAX - 1) + "…" : oneLine;
    }

    /** 用户可控的 planId 进日志前剥掉 CR/LF，防止伪造日志行 */
    private static String sanitize(String value) {
        return value == null ? null : value.replace("\r", "_").replace("\n", "_");
    }
}