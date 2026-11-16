package com.travel.agent.repository;

import com.travel.agent.entity.LlmQuotaUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface LlmQuotaUsageRepository extends JpaRepository<LlmQuotaUsage, Long> {

    Optional<LlmQuotaUsage> findByUserNameAndUsageDate(String userName, LocalDate usageDate);

    /**
     * 条件自增（原子闸门）：只有「尚未达到上限」的那一行会被 +1。
     *
     * 返回 1 = 本次调用抢到了名额（used_count 从 &lt; limit 变成 &lt;= limit）；
     * 返回 0 = 当日还没有计数行，或已用满（used_count &gt;= limit）。
     * 判断与写入在同一条 UPDATE 内完成，读-判-写之间没有缝隙，并发下放行次数严格等于 limit。
     *
     * 注意：必须放在事务里（LlmQuotaService#tryAcquire 已标注 @Transactional），
     * 否则 @Modifying 会抛 TransactionRequiredException。
     */
    @Modifying
    @Query(value = "UPDATE llm_quota_usage SET used_count = used_count + 1 "
            + "WHERE user_name = :userName AND usage_date = :usageDate AND used_count < :limit",
            nativeQuery = true)
    int incrementIfBelow(@Param("userName") String userName,
                         @Param("usageDate") LocalDate usageDate,
                         @Param("limit") int limit);

    /**
     * 幂等补行：当日首次调用时把计数行建出来（used_count = 0），已存在则什么都不改。
     *
     * 不用插入语句的影响行数来判断「是不是我建的」——MySQL 对 no-op 的
     * ON DUPLICATE KEY UPDATE 返回 0、H2 返回 1，方言不一致；而且并发首调时
     * 另一个事务可能刚插入未提交。这里只负责「让行存在」，放行与否一律由
     * 随后的 incrementIfBelow 条件自增的行数决定，语义单一且两种方言一致。
     *
     * 冲突分支写成 {@code usage_date = usage_date}（而不是 {@code used_count = used_count}）：
     * 等号右侧无论被解析成「已存在的行」还是「待插入的值」，拿到的都是同一个日期，
     * 因此这条赋值在任何方言下都严格是 no-op，不会把已累计的 used_count 改回 0。
     */
    @Modifying
    @Query(value = "INSERT INTO llm_quota_usage (user_name, usage_date, used_count) "
            + "VALUES (:userName, :usageDate, 0) "
            + "ON DUPLICATE KEY UPDATE usage_date = usage_date", nativeQuery = true)
    int insertIfAbsent(@Param("userName") String userName, @Param("usageDate") LocalDate usageDate);
}