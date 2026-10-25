package com.travel.agent.service;

import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话记忆：按「用户 + conversationId」存取最近的对话消息。
 *
 * conversationId 由前端生成并随请求携带，跨页面切换保持不变（sessionStorage）；
 * 服务端一切查询都以 userId 为准做归属隔离，客户端传什么都拿不到别人的历史。
 * 历史只喂给 LLM 意图识别做指代消解，规则引擎路径保持原样，行为可预期。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    /** 喂给模型的最近消息条数 */
    public static final int HISTORY_LIMIT = 10;
    /** 会话消息保留天数，超期清理 */
    private static final int RETENTION_DAYS = 7;
    /** 清理节流：最多每小时做一次全表过期清理 */
    private static final long PURGE_INTERVAL_MS = 60 * 60 * 1000;

    private final ConversationMessageRepository repository;
    private final AtomicLong nextPurgeAt = new AtomicLong();

    /** 最近一段对话，按时间正序返回；conversationId 为空视为单轮对话 */
    public List<ConversationMessage> recent(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return Collections.emptyList();
        }
        List<ConversationMessage> latest = repository
                .findTop20ByUserIdAndConversationIdOrderByCreatedAtDesc(userId, conversationId);
        Collections.reverse(latest);
        return latest.size() > HISTORY_LIMIT ? latest.subList(latest.size() - HISTORY_LIMIT, latest.size()) : latest;
    }

    /** 追加一条消息；conversationId 为空（单轮模式）时不落库 */
    @Transactional
    public void append(Long userId, String conversationId, String role, String content, String intent) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (content == null || content.isBlank()) {
            return;
        }
        repository.save(ConversationMessage.builder()
                .userId(userId)
                .conversationId(conversationId)
                .role(role)
                .content(truncate(content))
                .intent(intent)
                .build());
        purgeExpiredIfDue();
    }

    /** 开新会话：清掉该用户在这个会话里的历史 */
    @Transactional
    public void clear(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        repository.deleteByUserIdAndConversationId(userId, conversationId);
    }

    private static String truncate(String content) {
        return content.length() <= ConversationMessage.MAX_CONTENT_LENGTH
                ? content
                : content.substring(0, ConversationMessage.MAX_CONTENT_LENGTH);
    }

    private void purgeExpiredIfDue() {
        long now = System.currentTimeMillis();
        long current = nextPurgeAt.get();
        if (now < current || !nextPurgeAt.compareAndSet(current, now + PURGE_INTERVAL_MS)) {
            return;
        }
        try {
            long removed = repository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(RETENTION_DAYS));
            if (removed > 0) {
                log.info("清理过期会话消息 {} 条", removed);
            }
        } catch (Exception e) {
            // 清理失败不影响主流程，下一小时再试
            log.warn("过期会话消息清理失败: {}", e.getMessage());
        }
    }
}
