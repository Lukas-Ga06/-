package com.travel.agent.service;

import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.ScheduleItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 同步日程的幂等写入器：**独立事务**插入一条 {@code source_key} 日程，撞唯一键即视为已同步。
 *
 * <p>为什么必须是独立事务（REQUIRES_NEW）：
 * <ul>
 *   <li>{@code source_key} 的唯一索引（V22）是并发兜底；一旦真的撞上，Hibernate 会把
 *       当前事务标记为 rollback-only。若在同一个事务里 catch 掉异常继续往下写，
 *       提交时必然变成 {@code UnexpectedRollbackException}（500），
 *       而不是注释里承诺的「按已同步处理」；</li>
 *   <li>拆到独立事务后，内层回滚只丢弃这一天的重复插入，外层（同步台账 + 其余天 + 其它模块）不受影响。</li>
 * </ul>
 *
 * <p>为什么单独成 bean：Spring 的事务是代理实现的，放在 {@link PlanExecutionService} 内部
 * 自调用不会开启新事务（自调用绕过代理），幂等兜底会静默失效。
 */
@Service
@Slf4j
public class PlanScheduleSyncWriter {

    private final ScheduleItemRepository scheduleItemRepository;
    private final TransactionTemplate requiresNew;

    public PlanScheduleSyncWriter(ScheduleItemRepository scheduleItemRepository,
                                  PlatformTransactionManager transactionManager) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 插入一条同步日程。
     *
     * <p>与「整个同步一个大事务」的取舍：每日一条独立事务意味着外层台账事务若在极端情况下回滚，
     * 已提交的日程行会留下（用户看到日历块、但执行记录缺失）。这些行仍然挂在行程上，
     * 行程删除时由 {@code deleteByTripId} 一并清理，不会留下孤儿数据。
     *
     * @return true = 本次真的创建；false = 该 {@code source_key} 已存在（重复同步，按已同步处理）
     */
    public boolean insertIfAbsent(ScheduleItem item) {
        try {
            requiresNew.executeWithoutResult(status -> scheduleItemRepository.saveAndFlush(item));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 内层事务已随异常回滚，外层事务仍然健康
            log.debug("日程同步撞唯一键（按已同步处理）: {}", item.getSourceKey());
            return false;
        }
    }
}