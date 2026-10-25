package com.travel.agent.service;

import com.travel.agent.entity.LlmQuotaUsage;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.repository.LlmQuotaUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * LLM 每日按用户配额。
 *
 * - travel.llm.quota.daily-limit <= 0（默认 0）= 不限制；
 * - 用户名取自 UserContext（filter / AgentEngine 两处设置）；取不到（系统级后台调用）不限；
 * - 超限时返回 false，调用方（DelegatingLlmClient）把该次调用视为不可用返回 null，
 *   下游全部走既有降级路径（规则引擎/模板），用户体验无中断；
 * - 计数落库（用户名 + 日期唯一行，原子自增），重启不丢、多实例共享。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmQuotaService {

    private final LlmProperties llmProperties;
    private final LlmQuotaUsageRepository repository;

    /** 本次 LLM 调用是否放行；放行的调用才计数（被拒绝的请求不占用当日已用） */
    @Transactional
    public boolean tryAcquire(String username) {
        int limit = llmProperties.getQuotaDailyLimit();
        if (limit <= 0) {
            return true;
        }
        if (username == null || username.isBlank()) {
            return true; // 系统级调用（无用户上下文）不限额
        }
        LocalDate today = LocalDate.now();
        int used = repository.findByUserNameAndUsageDate(username, today)
                .map(LlmQuotaUsage::getUsedCount)
                .orElse(0);
        if (used >= limit) {
            log.debug("LLM 日配额已用尽 user={} used={} limit={}", username, used, limit);
            return false;
        }
        repository.increment(username, today);
        return true;
    }

    /** 当日已用次数（诊断/展示用，与闸门无关） */
    @Transactional(readOnly = true)
    public int usedToday(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        return repository.findByUserNameAndUsageDate(username, LocalDate.now())
                .map(LlmQuotaUsage::getUsedCount)
                .orElse(0);
    }
}
