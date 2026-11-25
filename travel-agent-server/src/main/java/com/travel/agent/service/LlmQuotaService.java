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
 *
 * 判断本身也必须原子：旧实现是「读 used → 比 limit → 自增」，N 个并发请求同时读到
 * used=limit-1 就全部放行，实际调用可达 N 倍超发（配额控成本，超发即真金白银）。
 * 现在闸门是条件自增 UPDATE 的影响行数：只有真正把 used_count 从 &lt;limit 加一的那次
 * 调用返回 1 并放行，并发下放行次数严格等于 limit。
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

        // 快路径：一条条件 UPDATE 同时完成「判断 + 占位」，rowcount=1 才算抢到名额
        if (repository.incrementIfBelow(username, today, limit) == 1) {
            logAcquired(username, today, limit, true);
            return true;
        }

        // rowcount=0 有两种可能：① 当日还没有计数行（首次调用）；② 已达上限。
        // 先幂等补行消除分支 ①，再走一次条件自增——放行与否仍然只由条件自增的行数决定，
        // 因此并发首调（两个事务同时补行）也能收敛到正确结果，不会重复放行。
        repository.insertIfAbsent(username, today);
        boolean allowed = repository.incrementIfBelow(username, today, limit) == 1;
        logAcquired(username, today, limit, allowed);
        return allowed;
    }

    /** 回读当日已用次数，用于日志与前端展示 */
    private void logAcquired(String username, LocalDate today, int limit, boolean allowed) {
        int used = repository.findByUserNameAndUsageDate(username, today)
                .map(LlmQuotaUsage::getUsedCount)
                .orElse(0);
        // username 来自登录身份（用户可控），写日志前剥掉 CR/LF
        String safeUser = username == null ? "null" : username.replace("\r", "_").replace("\n", "_");
        if (allowed) {
            log.debug("LLM 配额放行 user={} used={}/{}", safeUser, used, limit);
        } else {
            log.debug("LLM 日配额已用尽 user={} used={} limit={}", safeUser, used, limit);
        }
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