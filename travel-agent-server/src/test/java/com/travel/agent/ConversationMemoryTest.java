package com.travel.agent;

import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import com.travel.agent.service.ConversationService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话记忆：对话消息按「用户 + conversationId」落库，供后续轮次做指代消解。
 * 规则引擎路径的行为不因历史改变（LLM 关闭时语义照旧），这里验证存储与隔离契约。
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

    private void postChat(Account user, String conversationId, String message) throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"%s\",\"conversationId\":\"%s\"}"
                                .formatted(message, conversationId)))
                .andExpect(status().isOk());
    }
}
