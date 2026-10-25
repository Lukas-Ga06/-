package com.travel.agent.repository;

import com.travel.agent.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /** 最近 N 条（倒序取回后由调用方反转成时间正序） */
    List<ConversationMessage> findTop20ByUserIdAndConversationIdOrderByCreatedAtDesc(Long userId, String conversationId);

    /** 用户主动开新会话时清掉旧消息 */
    long deleteByUserIdAndConversationId(Long userId, String conversationId);

    /** 定期清理过期会话，避免表无限增长 */
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
