package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        seed(user, conversationId, firstUserMessage, at, null);
    }

    /** 带作用域的落库（V21）：scope 为 null 即「存量会话未绑定」 */
    private void seed(Account user, String conversationId, String firstUserMessage, LocalDateTime at,
                      String scope) {
        repository.save(ConversationMessage.builder()
                .userId(user.id())
                .conversationId(conversationId)
                .role(ConversationMessage.ROLE_USER)
                .content(firstUserMessage)
                .scope(scope)
                .createdAt(at)
                .build());
        repository.save(ConversationMessage.builder()
                .userId(user.id())
                .conversationId(conversationId)
                .role(ConversationMessage.ROLE_ASSISTANT)
                .content("好的，已记录。")
                .scope(scope)
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

        JsonNode payload = getJson(user, "/api/agent/conversations/conv-1/messages?limit=3");
        JsonNode messages = payload.path("messages");

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

        assertThat(asBob.path("messages").isArray()).isTrue();
        assertThat(asBob.path("messages")).isEmpty();
        // 连作用域都不给：否则「空数组」本身就成了存在性探针
        assertThat(asBob.path("scope").isNull()).isTrue();
    }

    @Test
    @DisplayName("没有会话时返回空数组，limit 超过上限不报错")
    void emptyListAndClampedLimit() throws Exception {
        Account user = registerNew();

        assertThat(getJson(user, "/api/agent/conversations")).isEmpty();
        // 上限收敛到 200：这里只验证不报错、仍是数组（数据量不足时不产生差异）
        assertThat(getJson(user, "/api/agent/conversations?limit=9999").isArray()).isTrue();
        assertThat(getJson(user, "/api/agent/conversations/unknown/messages").path("messages").isArray())
                .isTrue();
    }

    @Test
    @DisplayName("V21：列表回带会话作用域，存量会话为 null（不猜、不编造）")
    void summariesCarryScopeAndLegacyRowsStayNull() throws Exception {
        Account user = registerNew();
        LocalDateTime base = LocalDateTime.now().minusHours(3);
        seed(user, "conv-trip12", "把第二天下午的酒店换成地铁附近", base, "trip:12");
        seed(user, "conv-planner", "帮我排一个上海出发的三日游", base.plusMinutes(10), "planner");
        seed(user, "conv-legacy", "很久以前的对话", base.plusMinutes(20), null);

        JsonNode list = getJson(user, "/api/agent/conversations");

        assertThat(list).hasSize(3);
        assertThat(list.get(0).path("conversationId").asText()).isEqualTo("conv-legacy");
        // 存量行没有 scope：必须是显式的 null，不能回落成 'assistant' 之类的猜测值
        assertThat(list.get(0).path("scope").isNull()).isTrue();
        assertThat(list.get(1).path("conversationId").asText()).isEqualTo("conv-planner");
        assertThat(list.get(1).path("scope").asText()).isEqualTo("planner");
        assertThat(list.get(2).path("conversationId").asText()).isEqualTo("conv-trip12");
        assertThat(list.get(2).path("scope").asText()).isEqualTo("trip:12");
    }

    @Test
    @DisplayName("V21：续聊回填带出作用域（取该会话最近一条消息的值），行程绑定因此可恢复")
    void resumePayloadCarriesLatestScope() throws Exception {
        Account user = registerNew();
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        // 先在行程 12 里聊两句，再回到助手页聊一句：作用域跟着「最近一次使用」走
        seed(user, "conv-move", "上海行程第二天的酒店换掉", base, "trip:12");
        seed(user, "conv-move", "这个行程里还有哪些没安排的", base.plusMinutes(5), "trip:12");
        seed(user, "conv-move", "顺便记一下明天办签证", base.plusMinutes(10), "assistant");

        JsonNode payload = getJson(user, "/api/agent/conversations/conv-move/messages");

        assertThat(payload.path("conversationId").asText()).isEqualTo("conv-move");
        assertThat(payload.path("scope").asText())
                .as("续聊恢复的是「最近一次用在哪个页面」，不是「第一次」")
                .isEqualTo("assistant");
        assertThat(payload.path("messages")).hasSize(6);

        // 反向：最后一句仍在行程里，则回填仍是 trip:12
        seed(user, "conv-stay", "把第三天的景点删掉", base.plusMinutes(20), "trip:12");
        assertThat(getJson(user, "/api/agent/conversations/conv-stay/messages").path("scope").asText())
                .isEqualTo("trip:12");
    }

    @Test
    @DisplayName("V21：POST 对话的 scope 落库；未声明时按 tripId 推导（带 tripId 即 trip:<id>）")
    void chatPersistsDeclaredOrDerivedScope() throws Exception {
        Account user = registerNew();
        String declared = "conv-" + uniqueUsername();
        String derived = "conv-" + uniqueUsername();

        postChat(user, V21_JSON_CONV.formatted(declared) + "," + V21_JSON_SCOPE.formatted("trip:7"));
        // 老客户端：不带 scope
        postChat(user, V21_JSON_CONV.formatted(derived) + ",\"tripId\":9");

        assertThat(lastMessageScope(user, declared)).isEqualTo("trip:7");
        assertThat(lastMessageScope(user, derived))
                .as("请求体里已有 tripId，推导出的作用域来自请求本身，不是猜测")
                .isEqualTo("trip:9");
    }

    @Test
    @DisplayName("V21：非法 scope 当作未声明——不报错，按请求上下文推导，对话照常落库")
    void invalidScopeDegradesToDerived() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-" + uniqueUsername();

        // 注意：这里追问的是「scope 的脏值会不会让整条消息失去回复」，
        // 所以断言的是落库结果而不是 AgentResult.status（那是业务状态，不是 HTTP 状态）
        postChat(user, V21_JSON_CONV.formatted(conversationId) + ","
                + V21_JSON_SCOPE.formatted("trip 12 / ../../etc"));

        assertThat(repository.findAll())
                .as("脏 scope 不该让对话丢失：用户消息与助手回复都必须落库")
                .filteredOn(row -> conversationId.equals(row.getConversationId()))
                .hasSize(2);
        // 非法值被丢弃后等同于「老客户端没传」，于是走同一条推导规则（无 tripId → assistant）。
        // 关键是在没有 400、没有丢消息的前提下完成降级。
        assertThat(lastMessageScope(user, conversationId)).isEqualTo("assistant");
    }

    /** 测试用 JSON 片段：conversationId 与 scope 各一段，拼接时用逗号连接 */
    private static final String V21_JSON_CONV = "\"conversationId\":\"%s\"";
    private static final String V21_JSON_SCOPE = "\"scope\":\"%s\"";

    /** 发一条不带 tripId 的对话，extra 是拼进请求体的附加字段（形如 {@code ,"scope":"x"}） */
    private JsonNode postChat(Account user, String extra) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"message\":\"记一下明天办签证\",%s}").formatted(extra)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    /** 该会话最新一条消息的 scope（null 表示未绑定） */
    private String lastMessageScope(Account user, String conversationId) {
        return repository.findAll().stream()
                .filter(row -> Long.valueOf(user.id()).equals(row.getUserId()))
                .filter(row -> conversationId.equals(row.getConversationId()))
                .max(java.util.Comparator.comparing(ConversationMessage::getCreatedAt)
                        .thenComparing(ConversationMessage::getId))
                .orElseThrow()
                .getScope();
    }
}
