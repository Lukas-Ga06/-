package com.travel.agent.service;

import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 会话消息的单条写入器：**独立事务**插入一条消息，撞 {@code uk_conv_turn_role} 即返回 false。
 *
 * <p>为什么必须是独立事务（REQUIRES_NEW）：轮次序号是 compare-and-set 分配的
 * （{@code ConversationService#beginTurn} 取最大值 +1 后直接插入，撞键就重试）。
 * 一旦真的撞上，Hibernate 会把当前事务标记成 rollback-only；若在同一个事务里
 * catch 掉异常继续下一次尝试，提交时必然变成 {@code UnexpectedRollbackException}（500）。
 * 拆到独立事务后，内层回滚只丢弃这一次尝试，重试仍然干净。
 *
 * <p>为什么单独成 bean：Spring 的事务是代理实现的，放在 {@code ConversationService}
 * 内部自调用不会开启新事务（自调用绕过代理），CAS 会静默退化成普通插入。
 */
@Service
@Slf4j
public class ConversationTurnWriter {

    private final ConversationMessageRepository repository;
    private final TransactionTemplate requiresNew;

    public ConversationTurnWriter(ConversationMessageRepository repository,
                                  PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 尝试写入一条消息。
     *
     * @return true = 本次真的写入；false = 撞了 {@code (user_id, conversation_id, turn_seq, role)}
     *         唯一键（同一轮已被别的请求占用，调用方应换一个序号重试）
     */
    public boolean insertOnce(ConversationMessage message) {
        try {
            requiresNew.executeWithoutResult(status -> repository.saveAndFlush(message));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 内层事务已随异常回滚，调用方可以继续下一次尝试
            log.debug("会话轮次撞唯一键（换号重试）: user={} conversation={} turn={}",
                    message.getUserId(), message.getConversationId(), message.getTurnSeq());
            return false;
        }
    }
}