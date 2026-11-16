package com.travel.agent;

import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import com.travel.agent.service.ConversationService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话记忆：对话消息按「用户 + conversationId」落库，供后续轮次做指代消解。
 * 规则引擎路径的行为不因历史改变（LLM 关闭时语义照旧），这里验证存储、隔离、
 * 条数语义（limit 是消息条数不是轮数）与保留期清理。
 *
 * <p>条数断言一律读 {@code conversationService.historyLimit()} 而不是写死 10：
 * 集成方可以用 {@code travel.conversation.history-limit} 调整，测试要能跟着走。
 * 默认值 10 由 {@link ConversationConfigTest} 用显式属性覆盖来验证绑定。
 */
class ConversationMemoryTest extends IntegrationTestSupport {

    @Autowired
    private ConversationMessageRepository repository;

    @Autowired
    private ConversationService conversationService;

    @Test
    @DisplayName("带 conversationId 对话后，用户消息与助手回复都落库")
    void chatPersistsBothTurns() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-" + uniqueUsername();

        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"记一下明天办签证\",\"conversationId\":\"%s\"}"
                                .formatted(conversationId)))
                .andExpect(status().isOk());

        List<ConversationMessage> rows = repository.findTop20ByUserIdAndConversationIdOrderByCreatedAtDesc(
                user.id(), conversationId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ConversationMessage::getRole)
                .containsExactlyInAnyOrder(ConversationMessage.ROLE_USER, ConversationMessage.ROLE_ASSISTANT);
    }

    @Test
    @DisplayName("多轮消息递增落库；不同用户共用同一 conversationId 时互相隔离")
    void turnsAppendAndIsolatedPerUser() throws Exception {
        Account alice = registerNew();
        Account bob = registerNew();
        String conversationId = "conv-shared";

        postChat(alice, conversationId, "记一下明天办签证");
        postChat(alice, conversationId, "我的备忘有哪些");
        postChat(bob, conversationId, "记一下后天交材料");

        assertThat(conversationService.recent(alice.id(), conversationId))
                .hasSize(4)
                .allSatisfy(m -> assertThat(m.getUserId()).isEqualTo(alice.id()));
        assertThat(conversationService.recent(bob.id(), conversationId))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.getUserId()).isEqualTo(bob.id()));
    }

    @Test
    @DisplayName("不带 conversationId 时不落库（单轮模式行为不变）")
    void chatWithoutConversationIdStoresNothing() throws Exception {
        Account user = registerNew();
        long before = repository.count();

        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"记一下明天办签证\"}"))
                .andExpect(status().isOk());

        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("conversationId 非法时请求被拒绝（400）")
    void invalidConversationIdRejected() throws Exception {
        Account user = registerNew();

        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"conversationId\":\"bad id with space\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("新会话清空该用户该会话的历史")
    void clearRemovesOwnHistoryOnly() throws Exception {
        Account alice = registerNew();
        Account bob = registerNew();
        String conversationId = "conv-clear";

        postChat(alice, conversationId, "记一下明天办签证");
        postChat(bob, conversationId, "记一下后天交材料");

        conversationService.clear(alice.id(), conversationId);

        assertThat(conversationService.recent(alice.id(), conversationId)).isEmpty();
        assertThat(conversationService.recent(bob.id(), conversationId)).hasSize(2);
    }

    @Test
    @DisplayName("插 12 条：recent() 只返回最新 limit 条，且按时间正序")
    void recentReturnsLatestMessagesInChronologicalOrder() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-limit-" + uniqueUsername();
        LocalDateTime base = LocalDateTime.now().minusHours(3).withNano(0);
        insertMessages(user.id(), conversationId, base, 12);

        int limit = conversationService.historyLimit();
        assertThat(limit).as("history-limit 必须小于 12 才谈得上截断").isLessThan(12);

        List<ConversationMessage> rows = conversationService.recent(user.id(), conversationId);

        assertThat(rows).hasSize(limit);
        // 12 条里最新的 limit 条 = m{12-limit} .. m11，且是时间正序（旧 → 新）
        assertThat(rows).extracting(ConversationMessage::getContent)
                .containsExactlyElementsOf(IntStream.range(12 - limit, 12)
                        .mapToObj(i -> "m" + i)
                        .toList());
        assertThat(rows.get(0).getCreatedAt())
                .as("首条必须是最新 limit 条里最旧的那一条，不能是最早的 m0")
                .isEqualTo(base.plusMinutes(12L - limit));
        assertThat(rows.get(rows.size() - 1).getCreatedAt()).isEqualTo(base.plusMinutes(11));
    }

    @Test
    @DisplayName("history-limit 配小后（构造服务实例）截断立即生效，且不足 limit 时全返回")
    void historyLimitIsConfigurableAndCapsResults() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-small-" + uniqueUsername();
        LocalDateTime base = LocalDateTime.now().minusHours(2).withNano(0);
        insertMessages(user.id(), conversationId, base, 8);

        // 直接构造服务实例：验证「条数上限来自构造参数（即配置）」，不依赖 Spring 属性刷新
        ConversationService smallLimit = new ConversationService(repository, 3, ConversationService.DEFAULT_RETENTION_DAYS);
        assertThat(smallLimit.historyLimit()).isEqualTo(3);
        assertThat(smallLimit.recent(user.id(), conversationId))
                .extracting(ConversationMessage::getContent)
                .containsExactly("m5", "m6", "m7");

        // 配置比实际条数大：全返回，不补空
        ConversationService bigLimit = new ConversationService(repository, 50, ConversationService.DEFAULT_RETENTION_DAYS);
        assertThat(bigLimit.recent(user.id(), conversationId)).hasSize(8);
    }

    @Test
    @DisplayName("配置非法（≤0）时回落到默认值，不把记忆关成 0 条")
    void illegalConfigFallsBackToDefaults() {
        ConversationService broken = new ConversationService(repository, 0, -1);

        assertThat(broken.historyLimit()).isEqualTo(ConversationService.DEFAULT_HISTORY_LIMIT);
        assertThat(broken.retentionDays()).isEqualTo(ConversationService.DEFAULT_RETENTION_DAYS);
    }

    @Test
    @DisplayName("purgeExpired()：删除早于保留期的行，保留期内的行不动")
    void purgeExpiredRemovesOnlyRowsOlderThanRetention() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-purge-" + uniqueUsername();
        int retentionDays = conversationService.retentionDays();
        LocalDateTime now = LocalDateTime.now();

        ConversationMessage expired = repository.save(message(user.id(), conversationId, "user",
                "八天前的对话", now.minusDays(retentionDays + 1L)));
        ConversationMessage retained = repository.save(message(user.id(), conversationId, "user",
                "保留期内的对话", now.minusDays(retentionDays - 1L)));

        conversationService.purgeExpired();

        // 不断言返回条数：每日 4:30 的兜底任务若恰好在测试期间触发，过期行可能已被它删掉，
        // 但「过期行消失、保留期内行还在」这个语义两种情况下都必须成立。
        assertThat(repository.findById(expired.getId())).isEmpty();
        assertThat(repository.findById(retained.getId())).isPresent();
    }

    /** 按 base + i 分钟构造 12 条消息：createdAt 显式给定，保证顺序确定（不依赖写入时钟精度） */
    private void insertMessages(long userId, String conversationId, LocalDateTime base, int count) {
        for (int i = 0; i < count; i++) {
            repository.save(message(userId, conversationId,
                    i % 2 == 0 ? ConversationMessage.ROLE_USER : ConversationMessage.ROLE_ASSISTANT,
                    "m" + i, base.plusMinutes(i)));
        }
    }

    private ConversationMessage message(long userId, String conversationId, String role,
                                        String content, LocalDateTime createdAt) {
        return ConversationMessage.builder()
                .userId(userId)
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .createdAt(createdAt)
                .build();
    }

    private void postChat(Account user, String conversationId, String message) throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"%s\",\"conversationId\":\"%s\"}"
                                .formatted(message, conversationId)))
                .andExpect(status().isOk());
    }
}