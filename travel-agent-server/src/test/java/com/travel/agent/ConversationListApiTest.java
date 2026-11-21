package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 最近会话列表与「继续对话」回填（体验方案 §8.6）。
 *
 * <p>这是只读接口：不写业务数据、不调用模型。断言四件事：
 * <ol>
 *   <li>按最后活动时间倒序，标题取首条用户消息（超长截断）；</li>
 *   <li>严格按用户隔离（同一个 conversationId 在两个账号之间互不可见）；</li>
 *   <li>消息回填是时间正序，且 limit 取的是「最近 N 条」而不是「最前 N 条」；</li>
 *   <li>查询别人的会话返回空数组而不是 404（不能用它探测会话是否存在）。</li>
 * </ol>
 */
class ConversationListApiTest extends IntegrationTestSupport {

    @Autowired
    private ConversationMessageRepository repository;

    /** 直接落库并显式指定时间：接口按时间排序，测试不能依赖「写入恰好隔几毫秒」 */
    private void seed(Account user, String conversationId, String firstUserMessage, LocalDateTime at) {
        repository.save(ConversationMessage.builder()
                .userId(user.id())
                .conversationId(conversationId)
                .role(ConversationMessage.ROLE_USER)
                .content(firstUserMessage)
                .createdAt(at)
                .build());
        repository.save(ConversationMessage.builder()
                .userId(user.id())
                .conversationId(conversationId)
                .role(ConversationMessage.ROLE_ASSISTANT)
                .content("好的，已记录。")
                .createdAt(at.plusSeconds(1))
                .build());
    }

    private JsonNode getJson(Account user, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    @Test
    @DisplayName("最近会话按最后活动倒序，标题取首条用户消息，条数与时间齐全")
    void listsByLastActivityWithTitleFromFirstUserMessage() throws Exception {
        Account user = registerNew();
        LocalDateTime morning = LocalDateTime.now().minusHours(5);
        // 标题超长：验证截断，避免长段落把列表撑破
        String longTitle = "帮我规划一个上海出发的杭州周末两日游，两人同行，预算三千以内，"
                + "酒店要在地铁附近，第二天下午能早点回上海";
        seed(user, "conv-old", longTitle, morning);
        seed(user, "conv-new", "记一下明天办签证", morning.plusHours(3));

        JsonNode list = getJson(user, "/api/agent/conversations");

        assertThat(list).hasSize(2);
        // 倒序：后活动的 conv-new 在前
        assertThat(list.get(0).path("conversationId").asText()).isEqualTo("conv-new");
        assertThat(list.get(0).path("title").asText()).isEqualTo("记一下明天办签证");
        assertThat(list.get(0).path("messageCount").asInt()).isEqualTo(2);
        assertThat(list.get(0).path("lastMessageAt").asText()).isNotBlank();

        JsonNode old = list.get(1);
        assertThat(old.path("conversationId").asText()).isEqualTo("conv-old");
        assertThat(old.path("title").asText()).endsWith("…").hasSize(41);
    }

    @Test
    @DisplayName("会话列表严格按用户隔离：同一个 conversationId 也不会串到别人账号")
    void conversationsAreIsolatedPerUser() throws Exception {
        Account alice = registerNew();
        Account bob = registerNew();
        seed(alice, "conv-shared", "爱丽丝的计划", LocalDateTime.now().minusMinutes(10));
        seed(bob, "conv-shared", "鲍勃的计划", LocalDateTime.now());

        JsonNode aliceList = getJson(alice, "/api/agent/conversations");

        assertThat(aliceList).hasSize(1);
        assertThat(aliceList.get(0).path("title").asText()).isEqualTo("爱丽丝的计划");
        assertThat(aliceList.get(0).path("messageCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("继续对话回填：时间正序，limit 取最近 N 条")
    void messagesAreChronologicalAndTakeLatest() throws Exception {
        Account user = registerNew();
        LocalDateTime base = LocalDateTime.now().minusHours(2);
        seed(user, "conv-1", "第一句", base);
        seed(user, "conv-1", "第二句", base.plusMinutes(5));
        seed(user, "conv-1", "第三句", base.plusMinutes(10));

        JsonNode messages = getJson(user, "/api/agent/conversations/conv-1/messages?limit=3");

        // 三问三答共 6 条，limit=3 取「最近 3 条」（时间正序）：第二句的回复 → 第三句 → 第三句的回复。
        // 最早的「第一句」与其回复必须被裁掉 —— 这正是「取最近」而不是「取最前」。
        assertThat(messages).hasSize(3);
        assertThat(messages).extracting(node -> node.path("content").asText())
                .containsExactly("好的，已记录。", "第三句", "好的，已记录。");
        assertThat(messages).extracting(node -> node.path("role").asText())
                .containsExactly(ConversationMessage.ROLE_ASSISTANT, ConversationMessage.ROLE_USER,
                        ConversationMessage.ROLE_ASSISTANT);
        assertThat(messages.get(2).path("createdAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("查询别人的会话返回空数组（200），不泄露会话是否存在")
    void foreignConversationLooksEmpty() throws Exception {
        Account alice = registerNew();
        Account bob = registerNew();
        seed(alice, "conv-private", "只属于爱丽丝的对话", LocalDateTime.now());

        JsonNode asBob = getJson(bob, "/api/agent/conversations/conv-private/messages");

        assertThat(asBob.isArray()).isTrue();
        assertThat(asBob).isEmpty();
    }

    @Test
    @DisplayName("没有会话时返回空数组，limit 超过上限不报错")
    void emptyListAndClampedLimit() throws Exception {
        Account user = registerNew();

        assertThat(getJson(user, "/api/agent/conversations")).isEmpty();
        // 上限收敛到 200：这里只验证不报错、仍是数组（数据量不足时不产生差异）
        assertThat(getJson(user, "/api/agent/conversations?limit=9999").isArray()).isTrue();
        assertThat(getJson(user, "/api/agent/conversations/unknown/messages").isArray()).isTrue();
    }
}