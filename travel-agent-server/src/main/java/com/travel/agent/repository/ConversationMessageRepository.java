package com.travel.agent.repository;

import com.travel.agent.entity.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /**
     * 最近会话聚合（§8.6）：按 conversationId 分组，取最后活动时间与消息条数。
     * 排序与截断下推到数据库，避免把用户的全部历史加载到内存再排序。
     */
    interface ConversationSummaryView {
        String getConversationId();

        LocalDateTime getLastMessageAt();

        Long getMessageCount();
    }

    @Query("""
            select m.conversationId as conversationId,
                   max(m.createdAt) as lastMessageAt,
                   count(m) as messageCount
              from ConversationMessage m
             where m.userId = :userId
          group by m.conversationId
          order by max(m.createdAt) desc
            """)
    List<ConversationSummaryView> summarize(@Param("userId") Long userId, Pageable pageable);

    /**
     * 一批会话里的「首条某角色消息」：用来给会话列表取标题（用户第一句话最能说明聊的是什么）。
     * 结果按 conversationId + 时间正序返回，调用方按会话取第一条即可。
     */
    @Query("""
            select m from ConversationMessage m
             where m.userId = :userId and m.role = :role and m.conversationId in :conversationIds
          order by m.conversationId asc, m.createdAt asc, m.id asc
            """)
    List<ConversationMessage> findFirstOfRole(@Param("userId") Long userId,
                                              @Param("role") String role,
                                              @Param("conversationIds") Collection<String> conversationIds);

    /** 某个会话最近的 N 条（倒序取回，由调用方反转成时间正序） */
    List<ConversationMessage> findByUserIdAndConversationIdOrderByCreatedAtDescIdDesc(
            Long userId, String conversationId, Pageable pageable);

    /** 最近 N 条（倒序取回后由调用方反转成时间正序） */
    List<ConversationMessage> findTop20ByUserIdAndConversationIdOrderByCreatedAtDesc(Long userId, String conversationId);

    /** 用户主动开新会话时清掉旧消息 */
    long deleteByUserIdAndConversationId(Long userId, String conversationId);

    /** 定期清理过期会话，避免表无限增长 */
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
