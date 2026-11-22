package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import com.travel.agent.service.ConversationService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话管理（V23）：重命名与删除。
 *
 * <p>此前「最近会话」抽屉只能继续对话或开新会话，改名和删单段会话都没有入口 ——
 * 用户想清理一次跑偏的对话，只能等 7 天保留期到期。
 *
 * <p>断言：改的名字压过推导标题；空标题恢复自动标题；删除后列表与回填都拿不到；
 * 别人的会话改不动也删不掉（一切按 userId 隔离，不靠 conversationId 保密）。
 */
class ConversationManageApiTest extends IntegrationTestSupport {

    @Autowired
    private ConversationMessageRepository repository;

    @Autowired
    private ConversationService conversationService;

    /** 直接落一组问答：这些用例只关心「列表怎么展示 / 能不能删」，不需要真跑一轮 Agent */
    private void seed(Account user, String conversationId, String firstUserMessage) {
        repository.save(ConversationMessage.builder()
                .userId(user.id())
                .conversationId(conversationId)
                .role(ConversationMessage.ROLE_USER)
                .content(firstUserMessage)
                .build());
        repository.save(ConversationMessage.builder()
                .userId(user.id())
                .conversationId(conversationId)
                .role(ConversationMessage.ROLE_ASSISTANT)
                .content("好的，已记录。")
                .build());
    }

    private JsonNode getJson(Account user, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    @Test
    @DisplayName("重命名：列表标题变成用户改的名字，消息本身不动")
    void renameOverridesDerivedTitle() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-rename-" + uniqueUsername();
        seed(user, conversationId, "帮我规划杭州两日游");

        JsonNode renamed = body(mockMvc.perform(patch("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"杭州周末\"}"))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(renamed.path("title").asText()).isEqualTo("杭州周末");
        assertThat(renamed.path("conversationId").asText()).isEqualTo(conversationId);

        JsonNode list = getJson(user, "/api/agent/conversations");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("title").asText()).isEqualTo("杭州周末");
        // 改名是展示层的事：消息条数、回填内容都不受影响
        assertThat(list.get(0).path("messageCount").asInt()).isEqualTo(2);
        assertThat(getJson(user, "/api/agent/conversations/" + conversationId + "/messages")
                .path("messages")).hasSize(2);
    }

    @Test
    @DisplayName("重命名传空标题：恢复自动标题（回到首条用户消息）")
    void blankTitleRestoresDerivedTitle() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-restore-" + uniqueUsername();
        seed(user, conversationId, "帮我看看北京天气");

        mockMvc.perform(patch("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"临时名字\"}"))
                .andExpect(status().isOk());

        JsonNode restored = body(mockMvc.perform(patch("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(restored.path("title").isNull())
                .as("空标题 = 恢复自动标题，服务端回传 null 表示「没有覆盖」")
                .isTrue();
        assertThat(getJson(user, "/api/agent/conversations").get(0).path("title").asText())
                .isEqualTo("帮我看看北京天气");
    }

    @Test
    @DisplayName("删除：列表与回填都拿不到，重复删除仍然成功（幂等）")
    void deleteRemovesConversationAndIsIdempotent() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-delete-" + uniqueUsername();
        seed(user, conversationId, "这段对话跑偏了");
        assertThat(getJson(user, "/api/agent/conversations")).hasSize(1);

        mockMvc.perform(delete("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk());
        assertThat(getJson(user, "/api/agent/conversations")).isEmpty();
        assertThat(getJson(user, "/api/agent/conversations/" + conversationId + "/messages")
                .path("messages")).isEmpty();

        // 再删一次：不报错（保留期清理和手动删除可能撞在一起）
        mockMvc.perform(delete("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("删除会连标题覆盖一起清掉：重建同名会话后回到自动标题")
    void deleteAlsoDropsRenamedTitle() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-reuse-" + uniqueUsername();
        seed(user, conversationId, "第一次聊");
        conversationService.rename(user.id(), conversationId, "旧名字");

        mockMvc.perform(delete("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk());

        seed(user, conversationId, "用同一个 id 再聊一次");
        assertThat(getJson(user, "/api/agent/conversations").get(0).path("title").asText())
                .as("删除必须连 conversation_meta 一起删，否则旧标题会粘到新会话上")
                .isEqualTo("用同一个 id 再聊一次");
    }

    @Test
    @DisplayName("改名与删除严格按用户隔离：别人的会话改不动也删不掉")
    void renameAndDeleteAreIsolatedPerUser() throws Exception {
        Account alice = registerNew();
        Account bob = registerNew();
        String conversationId = "conv-iso-" + uniqueUsername();
        seed(alice, conversationId, "爱丽丝的计划");
        seed(bob, conversationId, "鲍勃的计划");

        // 鲍勃对同一个 conversationId 改名 / 删除
        mockMvc.perform(patch("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", bob.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"鲍勃改的\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/agent/conversations/{id}", conversationId)
                        .header("Authorization", bob.bearer()))
                .andExpect(status().isOk());

        // 爱丽丝的会话毫发无伤：标题原样、消息还在
        JsonNode aliceList = getJson(alice, "/api/agent/conversations");
        assertThat(aliceList).hasSize(1);
        assertThat(aliceList.get(0).path("title").asText()).isEqualTo("爱丽丝的计划");
        assertThat(aliceList.get(0).path("messageCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("非法 conversationId 不会打到别人身上：清洗后按「无此会话」处理")
    void invalidConversationIdIsIgnored() throws Exception {
        Account user = registerNew();
        seed(user, "conv-keep-" + uniqueUsername(), "保留这段");

        // 长度超过 64 的 id 会被清洗成 null：改名回 null 标题、删除删 0 行，都不影响已有会话
        String tooLong = "x".repeat(80);
        mockMvc.perform(delete("/api/agent/conversations/{id}", tooLong)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk());
        assertThat(getJson(user, "/api/agent/conversations")).hasSize(1);
    }
}