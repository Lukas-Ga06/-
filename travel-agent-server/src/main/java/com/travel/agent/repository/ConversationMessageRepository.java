package com.travel.agent.repository;

import com.travel.agent.entity.ConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /**
     * 最近会话聚合（§8.6）：按 conversationId 分组，取最后活动时间与消息条数。
     * 排序与截断下推到数据库，避免把用户的全部历史加载到内存再排序。
     */
    interface ConversationSummaryView {
        String getConversationId();

        LocalDateTime getLastMessageAt();

        Long getMessageCount();

        /** 作用域（V21）；存量行为 null，这里用 coalesce 折叠成空串，Java 侧再还原成 null */
        String getScope();
    }

    /**
     * 最近会话聚合（§8.6）：按 conversationId 分组，取最后活动时间、消息条数与作用域。
     *
     * <p>排序与截断下推到数据库，避免把用户的全部历史加载到内存再排序。
     * scope 取该会话的 <b>最新值</b>：scope 每轮写入时都会刷新，所以「最后一条消息的 scope」
     * 就是「这段会话最近一次被用在哪个页面」，也是续聊时应该恢复的那个绑定。
     * max() 对字符串取的是字典序最大值，但同一会话的所有行 scope 相同，结果等价且不增加子查询。
     */
    @Query("""
            select m.conversationId as conversationId,
                   max(m.createdAt) as lastMessageAt,
                   count(m) as messageCount,
                   coalesce(max(m.scope), '') as scope
              from ConversationMessage m
             where m.userId = :userId
          group by m.conversationId
          order by max(m.createdAt) desc
            """)
    List<ConversationSummaryView> summarize(@Param("userId") Long userId, Pageable pageable);

    /** 某个会话最近的一条（取它携带的作用域，用于续聊回填时恢复行程绑定） */
    Optional<ConversationMessage> findFirstByUserIdAndConversationIdOrderByCreatedAtDescIdDesc(
            Long userId, String conversationId);

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
