package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import com.travel.agent.service.BudgetService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.ToolInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第十批 §5：会话记忆以「受限摘要」进入规划 Prompt。
 *
 * <p>只测正向/边界：条数上限 4、每条 120 字截断、无历史时 Prompt 与旧版逐字一致、
 * 历史里的金额只出现在历史块里且不进入产物。既有断言一律不动。
 */
class PlannerHistoryPromptTest {

    /** 捕获 Prompt 的桩客户端；返回固定合法 JSON，让 planNewTrip 走到产物 */
    private static class StubLlmClient implements LlmClient {
        private final String response;
        final List<String> userPrompts = new ArrayList<>();
        int calls = 0;

        StubLlmClient(String response) {
            this.response = response;
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls++;
            userPrompts.add(messages.get(messages.size() - 1).content());
            return response;
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final String VALID_JSON = """
            {"reasoning":"按清单编排","days":[{"day":1,"items":[
              {"type":"hotel","name":"城心酒店","startTime":"09:00","endTime":"09:30"},
              {"type":"attraction","name":"故宫","startTime":"10:00","endTime":"11:45"},
              {"type":"restaurant","name":"老字号烤鸭","startTime":"12:00","endTime":"13:00"}]}]}
            """;

    private final CityCatalog city = new CityCatalog(
            "测试市",
            List.of(
                    new Attraction("a1", "故宫", "outdoor", List.of("历史"), 39.9, 116.4, 60, 2, 4.8, "明清皇宫"),
                    new Attraction("a2", "科技馆", "indoor", List.of("亲子"), 39.8, 116.5, 30, 2.5, 4.6, "科技展览")),
            List.of(new Hotel("h1", "城心酒店", true, 400, 4.7, 39.95, 116.42, "市中心")),
            List.of(new Restaurant("r1", "老字号烤鸭", "京菜", 120, 4.6, 39.91, 116.41, "烤鸭")));

    private final StructuredPlanner.TripDraftView draft =
            new StructuredPlanner.TripDraftView("测试市", 3, LocalDate.of(2026, 11, 1),
                    BigDecimal.valueOf(3000), List.of());

    private StructuredPlanner planner(LlmClient client) {
        return new StructuredPlanner(client, new ObjectMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static ConversationMessage msg(String role, String content) {
        return ConversationMessage.builder().role(role).content(content).build();
    }

    /** 历史块里的 role 行（user/assistant 各占一行） */
    private static List<String> roleLines(String prompt) {
        return prompt.lines().filter(l -> l.startsWith("user: ") || l.startsWith("assistant: ")).toList();
    }

    @Test
    @DisplayName("3 条历史 → Prompt 含 3 行 role: content，超长内容截断到 120 字 + …")
    void threeMessagesRenderOneLineEachWithTruncation() {
        String longContent = "旧".repeat(200);
        List<ConversationMessage> history = List.of(
                msg(ConversationMessage.ROLE_USER, "帮我规划上海三日游"),
                msg(ConversationMessage.ROLE_ASSISTANT, longContent),
                msg(ConversationMessage.ROLE_USER, "那把第二天的\n酒店也换掉"));

        StubLlmClient client = new StubLlmClient(VALID_JSON);
        StructuredPlanner.NewTripParse parse = planner(client).planNewTrip(city, draft, "那把第二天的酒店也换掉", history);

        assertThat(parse.ok()).isTrue();
        String prompt = client.userPrompts.get(0);
        assertThat(prompt).contains(StructuredPlanner.HISTORY_BLOCK_TITLE);

        List<String> lines = roleLines(prompt);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("user: 帮我规划上海三日游");
        // 第 2 条 200 字 → 截断为 120 字 + "…"（共 121 字）
        String truncated = lines.get(1).substring("assistant: ".length());
        assertThat(truncated).hasSize(StructuredPlanner.HISTORY_MAX_CHARS + 1);
        assertThat(truncated).endsWith(StructuredPlanner.HISTORY_ELLIPSIS);
        assertThat(truncated).isEqualTo("旧".repeat(120) + "…");
        // 换行被压成单行，保证「一条历史一行」
        assertThat(lines.get(2)).isEqualTo("user: 那把第二天的 酒店也换掉");
        assertThat(prompt.lines().filter(l -> l.startsWith("user: ") || l.startsWith("assistant: ")).count())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("6 条历史 → 只出现最近 4 条（时间正序），更早的 2 条不出现")
    void onlyLatestFourMessagesAreInjected() {
        List<ConversationMessage> history = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            history.add(msg(i % 2 == 1 ? ConversationMessage.ROLE_USER : ConversationMessage.ROLE_ASSISTANT, "M" + i + " 的旧内容"));
        }

        StubLlmClient client = new StubLlmClient(VALID_JSON);
        planner(client).planNewTrip(city, draft, "继续", history);

        String prompt = client.userPrompts.get(0);
        assertThat(roleLines(prompt)).hasSize(StructuredPlanner.HISTORY_MAX_MESSAGES);
        assertThat(prompt).doesNotContain("M1 的旧内容").doesNotContain("M2 的旧内容");
        for (int i = 3; i <= 6; i++) {
            assertThat(prompt).contains("M" + i + " 的旧内容");
        }
        assertThat(roleLines(prompt)).containsExactly(
                "user: M3 的旧内容", "assistant: M4 的旧内容",
                "user: M5 的旧内容", "assistant: M6 的旧内容");
    }

    @Test
    @DisplayName("历史为空 → Prompt 不含历史块，与旧 Prompt 逐字一致")
    void emptyHistoryLeavesPromptUnchanged() {
        List<ConversationMessage> empty = List.of();
        assertThat(StructuredPlanner.historyBlock(empty)).isEmpty();
        assertThat(StructuredPlanner.historyBlock(null)).isEmpty();

        // 旧签名（无 history）与新签名（空历史）必须产出完全相同的 Prompt
        StructuredPlanner planner = planner(new StubLlmClient(VALID_JSON));
        String legacy = planner.newTripPrompt(city, draft, "帮我规划测试市三日游");
        String withEmpty = planner.newTripPrompt(city, draft, "帮我规划测试市三日游", empty);
        assertThat(withEmpty).isEqualTo(legacy);
        assertThat(withEmpty).doesNotContain(StructuredPlanner.HISTORY_BLOCK_TITLE);
        assertThat(roleLines(withEmpty)).isEmpty();

        // 走到真实 planNewTrip 也一样：本轮 Prompt 里没有历史块
        StubLlmClient client = new StubLlmClient(VALID_JSON);
        planner(client).planNewTrip(city, draft, "帮我规划测试市三日游");
        assertThat(client.userPrompts.get(0)).isEqualTo(legacy);

        // 修改路径同理
        String modifyLegacy = planner.modificationPrompt(city, List.of(existing()), "晴", "换掉故宫");
        assertThat(planner.modificationPrompt(city, List.of(existing()), "晴", "换掉故宫", empty))
                .isEqualTo(modifyLegacy);
        assertThat(modifyLegacy).doesNotContain(StructuredPlanner.HISTORY_BLOCK_TITLE);
    }

    @Test
    @DisplayName("历史里的 ¥8000 只出现在历史块里，产物金额仍来自候选清单")
    void historyAmountDoesNotLeakIntoArtifacts() {
        List<ConversationMessage> history = List.of(
                msg(ConversationMessage.ROLE_USER, "上次方案总预算 ¥8000，酒店住两晚"),
                msg(ConversationMessage.ROLE_ASSISTANT, "好的，已按 ¥8000 预算安排"));

        StubLlmClient client = new StubLlmClient(VALID_JSON);
        StructuredPlanner.NewTripParse parse = planner(client).planNewTrip(city, draft, "重新安排一下", history);

        String prompt = client.userPrompts.get(0);
        assertThat(prompt).contains("¥8000");
        // 只出现在历史块：块头之前的正文（天数/预算/候选清单）里没有它
        int headerAt = prompt.indexOf(StructuredPlanner.HISTORY_BLOCK_TITLE);
        assertThat(headerAt).isGreaterThan(0);
        assertThat(prompt.substring(0, headerAt)).doesNotContain("8000").doesNotContain("¥8000");
        assertThat(prompt).contains("总预算 3000 元");

        // 产物金额来自规则/候选清单：酒店 400×2=800、门票 60、餐 120，合计 980，与历史里的 8000 无关
        assertThat(parse.ok()).isTrue();
        BigDecimal total = parse.drafts().stream()
                .map(ItineraryItem::getCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo(BigDecimal.valueOf(980));
        assertThat(total).isNotEqualByComparingTo(BigDecimal.valueOf(8000));
        assertThat(parse.drafts()).noneMatch(i -> BigDecimal.valueOf(8000).compareTo(i.getCost()) == 0);
        assertThat(parse.drafts().get(0).getCost()).isEqualByComparingTo(BigDecimal.valueOf(800));
    }

    @Test
    @DisplayName("修改 Prompt 同样只在有历史时追加历史块（最近 4 条 / 120 字规则一致）")
    void modificationPromptAlsoCarriesRestrictedHistory() {
        List<ConversationMessage> history = List.of(
                msg(ConversationMessage.ROLE_USER, "把故宫换成室内的"),
                msg(ConversationMessage.ROLE_ASSISTANT, "已换成科技馆"));
        String json = "{\"reasoning\":\"换室内\",\"operations\":[{\"type\":\"ADD\",\"name\":\"科技馆\",\"dayIndex\":1,"
                + "\"startTime\":\"14:00\",\"endTime\":\"16:30\"}]}";

        StubLlmClient client = new StubLlmClient(json);
        StructuredPlanner.ModificationParse parse = planner(client)
                .planModification(city, List.of(existing()), LocalDate.of(2026, 11, 1), "小雨", "第二天的也换", history);

        assertThat(parse.ok()).isTrue();
        String prompt = client.userPrompts.get(0);
        assertThat(prompt).contains(StructuredPlanner.HISTORY_BLOCK_TITLE);
        assertThat(roleLines(prompt)).containsExactly("user: 把故宫换成室内的", "assistant: 已换成科技馆");
    }

    @Test
    @DisplayName("PlannerAgent 在 LLM 路径把 ctx.getHistory() 传进结构化规划 Prompt")
    void plannerAgentPassesContextHistoryIntoPrompt() {
        StubLlmClient client = new StubLlmClient(VALID_JSON);
        StructuredPlanner structured = planner(client);
        LlmProperties props = new LlmProperties();
        props.setEnabled(true);
        props.setApiKey("test-key");
        TravelCatalogService catalog = mock(TravelCatalogService.class);
        when(catalog.matchCity(anyString())).thenReturn(city);
        BudgetService budgetService = mock(BudgetService.class);
        when(budgetService.totalOf(any())).thenReturn(BigDecimal.valueOf(980));
        PlannerAgent plannerAgent = new PlannerAgent(mock(ToolInvoker.class), catalog,
                mock(ItineraryService.class), budgetService, props, structured);

        AgentContext ctx = new AgentContext();
        ctx.setMessage("帮我规划测试市三日游");
        ctx.setHistory(List.of(msg(ConversationMessage.ROLE_USER, "上次那趟先别管")));
        IntentResult intent = IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP).target(IntentResult.TARGET_NONE)
                .constraints(Map.of()).build();

        AgentPlan plan = plannerAgent.plan(ctx, intent, null);

        assertThat(plan).isNotNull();
        assertThat(client.calls).isEqualTo(1);
        assertThat(client.userPrompts.get(0))
                .contains(StructuredPlanner.HISTORY_BLOCK_TITLE)
                .contains("user: 上次那趟先别管");
    }

    @Test
    @DisplayName("未配置 LLM（规则路径）→ 不调用模型、不渲染历史块，行为不变")
    void rulePathNeverCallsLlm() {
        StubLlmClient client = new StubLlmClient(VALID_JSON);
        LlmProperties props = new LlmProperties(); // enabled=false → usable()=false
        TravelCatalogService catalog = mock(TravelCatalogService.class);
        when(catalog.matchCity(anyString())).thenReturn(city);
        BudgetService budgetService = mock(BudgetService.class);
        when(budgetService.totalOf(any())).thenReturn(BigDecimal.ZERO);
        PlannerAgent plannerAgent = new PlannerAgent(mock(ToolInvoker.class), catalog,
                mock(ItineraryService.class), budgetService, props, planner(client));

        AgentContext ctx = new AgentContext();
        ctx.setMessage("帮我规划测试市三日游");
        ctx.setHistory(List.of(msg(ConversationMessage.ROLE_USER, "含 ¥8000 的旧方案")));
        IntentResult intent = IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP).target(IntentResult.TARGET_NONE)
                .constraints(Map.of()).build();

        AgentPlan plan = plannerAgent.plan(ctx, intent, null);

        assertThat(plan).isNotNull();
        assertThat(client.calls).isZero();
        assertThat(client.userPrompts).isEmpty();
    }

    private static ItineraryDtos.ItineraryItemDTO existing() {
        ItineraryDtos.ItineraryItemDTO dto = new ItineraryDtos.ItineraryItemDTO();
        dto.setId(11L);
        dto.setDayIndex(1);
        dto.setItemType(ItineraryItem.TYPE_ATTRACTION);
        dto.setTitle("故宫");
        return dto;
    }
}