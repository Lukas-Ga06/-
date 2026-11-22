package com.travel.agent.repository;

import com.travel.agent.entity.ConversationMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 会话元数据（V23）：改名用的标题覆盖，按 (user_id, conversation_id) 一行。 */
public interface ConversationMetaRepository extends JpaRepository<ConversationMeta, Long> {

    Optional<ConversationMeta> findByUserIdAndConversationId(Long userId, String conversationId);

    List<ConversationMeta> findByUserIdAndConversationIdIn(Long userId, Collection<String> conversationIds);

    long deleteByUserIdAndConversationId(Long userId, String conversationId);
}