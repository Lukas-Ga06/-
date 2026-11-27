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
import java.util.Optional;
import java.util.UUID;

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
                              String planRevision, String contentHash, Instant createdAt) {
    }

    private final PendingPlanRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void put(PendingPlan pending) {
        try {
            String planJson = objectMapper.writeValueAsString(pending.plan());
            // 内容指纹（FR-08）：对 plan_json 计算 SHA-256，并回填到确认请求上——
            // 前端把它随确认请求带回，后端校验「用户确认的就是看到的内容」
            String contentHash = sha256(planJson);
            stampContentHash(pending.preview(), contentHash);
            repository.save(PendingPlanRecord.builder()
                    .planId(pending.planId())
                    .userId(pending.userId())
                    .tripId(pending.tripId())
                    .planJson(planJson)
                    .previewJson(objectMapper.writeValueAsString(pending.preview()))
                    .planRevision(pending.planRevision())
                    .contentHash(contentHash)
                    .createdAt(LocalDateTime.ofInstant(pending.createdAt(), ZoneId.systemDefault()))
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("待确认方案序列化失败", e);
        }
        purgeExpired();
    }

    /** 把内容指纹写进确认请求（NEED_CONFIRMATION 的 preview 才有 confirmation 字段） */
    private static void stampContentHash(AgentDtos.AgentResult preview, String contentHash) {
        if (preview != null && preview.getConfirmation() != null) {
            preview.getConfirmation().setContentHash(contentHash);
        }
    }

    /** SHA-256 十六进制（64 字符）；计算失败返回 null（退化为「未绑定指纹」的旧校验） */
    static String sha256(String text) {
        if (text == null) {
            return null;
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
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

    /**
     * 抢占确认/取消的所有权：条件更新 PENDING → CONFIRMING，并发放本次租约令牌。
     *
     * 返回非空令牌的调用方是这份方案唯一的处理者（并发下数据库行锁保证只有一个成功）；
     * 返回空说明方案不存在、不属于该用户，或已被别的请求确认/取消。
     * 独立事务：必须让「抢到所有权」这一步立即提交，否则后续长事务提交前
     * 其它请求看到的仍是 PENDING，原子性就失效了。
     */
    @Transactional
    public Optional<String> claim(String planId, Long userId) {
        if (planId == null || userId == null) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        boolean claimed = repository.claim(planId, userId, token, LocalDateTime.now()) == 1;
        if (!claimed) {
            // planId 来自请求（用户可控），写入日志前剥掉 CR/LF，防止伪造日志行
            log.debug("待确认方案已被抢占或不存在: planId={} userId={}",
                    planId.replace("\r", "_").replace("\n", "_"), userId);
            return Optional.empty();
        }
        return Optional.of(token);
    }

    /**
     * 释放所有权：提交失败/被拒绝时把 CONFIRMING 放回 PENDING，
     * 让用户可以用同一个 planId 重试（不用重新发起规划）。
     *
     * 只有令牌匹配才会回退：行若已被清扫或被别的请求重新 claim，
     * 本次释放什么都不做（不能动别人的租约）。返回 true = 确实由本租约释放。
     */
    @Transactional
    public boolean release(String planId, String token) {
        if (planId == null || token == null) {
            return false;
        }
        boolean released = repository.release(planId, token) == 1;
        if (!released) {
            log.debug("待确认方案无需释放（已不是本租约持有）: planId={}",
                    planId.replace("\r", "_").replace("\n", "_"));
        }
        return released;
    }

    /**
     * 清扫过期确认租约：claimed_at 早于 threshold 的 CONFIRMING 回到 PENDING。
     * 崩溃恢复路径，返回清扫条数；日志只写条数，不写方案内容。
     */
    @Transactional
    public int sweepStale(LocalDateTime threshold) {
        if (threshold == null) {
            return 0;
        }
        int swept = repository.sweepStale(threshold);
        if (swept > 0) {
            log.info("清扫过期确认租约 {} 条：CONFIRMING 已回到 PENDING", swept);
        }
        return swept;
    }

    /**
     * 提交栅栏：删除「仍由本租约持有」的方案行，返回是否删除成功。
     *
     * 调用方必须在提交事务内检查返回值：false 说明租约已失效（被清扫/被重抢），
     * 必须抛异常回滚整个提交，防止重复落库。
     */
    @Transactional
    public boolean deleteClaimed(String planId, String token) {
        if (planId == null || token == null) {
            return false;
        }
        boolean deleted = repository.deleteClaimed(planId, token) == 1;
        if (!deleted) {
            log.warn("确认提交栅栏未通过：方案已不归本租约持有 planId={}",
                    planId.replace("\r", "_").replace("\n", "_"));
        }
        return deleted;
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
                    record.getContentHash(),
                    record.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            // 反序列化失败说明这条记录已经不可用，直接清掉，避免反复报错
            log.error("待确认方案反序列化失败，已清理: planId={}", record.getPlanId(), e);
            repository.deleteById(record.getPlanId());
            return null;
        }
    }
}
