package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Restaurant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结构化规划重试：首轮完全失败时带校验反馈重试一次，部分有效不重试 */
class StructuredPlannerRetryTest {

    /** 按顺序返回预设响应的桩客户端 */
    private static class StubLlmClient implements LlmClient {
        private final List<String> responses;
        private int index = 0;
        final List<String> prompts = new ArrayList<>();

        StubLlmClient(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            prompts.add(messages.get(1).content());
            String value = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return value;
        }

        @Override
        public boolean available() {
            return true;
        }

        int calls() {
            return index;
        }
    }

    private final CityCatalog city = new CityCatalog(
            "测试市",
            List.of(
                    new Attraction("a1", "故宫", "outdoor", List.of("历史"), 39.9, 116.4, 60, 2, 4.8, "明清皇宫"),
                    new Attraction("a2", "科技馆", "indoor", List.of("亲子"), 39.8, 116.5, 30, 2.5, 4.6, "科技展览")),
            List.of(),
            List.of(new Restaurant("r1", "老字号烤鸭", "京菜", 120, 4.6, 39.91, 116.41, "烤鸭")));

    private final StructuredPlanner.TripDraftView draft =
            new StructuredPlanner.TripDraftView("测试市", 2, LocalDate.of(2026, 11, 1),
                    BigDecimal.valueOf(3000), List.of());

    private static final String VALID = """
            {"reasoning":"修正后的编排","days":[{"day":1,"items":[
              {"type":"attraction","name":"故宫","startTime":"09:00","endTime":"11:30"}]}]}
            """;

    @Test
    void retriesOnceWithFeedbackWhenFirstAttemptFullyInvalid() {
        StubLlmClient client = new StubLlmClient(List.of(
                "{\"reasoning\":\"r\",\"days\":[{\"day\":1,\"items\":[{\"type\":\"attraction\",\"name\":\"编造景点\",\"startTime\":\"09:00\",\"endTime\":\"10:00\"}]}]}",
                VALID));
        StructuredPlanner planner = new StructuredPlanner(client, new ObjectMapper());
        StructuredPlanner.NewTripParse parse = planner.planNewTrip(city, draft, "两日游");
        assertTrue(parse.ok());
        assertEquals("故宫", parse.drafts().get(0).getTitle());
        assertEquals(2, client.calls(), "首轮全无效 → 恰好重试一次");
        assertTrue(client.prompts.get(1).contains("编造景点"), "重试提示词应带上首轮校验反馈");
        assertTrue(client.prompts.get(1).contains("必须全部修正"));
    }

    @Test
    void malformedJsonAlsoTriggersRetry() {
        StubLlmClient client = new StubLlmClient(List.of("这不是 JSON", VALID));
        StructuredPlanner planner = new StructuredPlanner(client, new ObjectMapper());
        StructuredPlanner.NewTripParse parse = planner.planNewTrip(city, draft, "两日游");
        assertTrue(parse.ok());
        assertEquals(2, client.calls());
        assertTrue(client.prompts.get(1).contains("不是合法 JSON"));
    }

    @Test
    void noRetryWhenFirstAttemptPartiallyValid() {
        StubLlmClient client = new StubLlmClient(List.of(
                "{\"reasoning\":\"r\",\"days\":[{\"day\":1,\"items\":["
                        + "{\"type\":\"attraction\",\"name\":\"编造景点\",\"startTime\":\"09:00\",\"endTime\":\"10:00\"},"
                        + "{\"type\":\"attraction\",\"name\":\"故宫\",\"startTime\":\"10:00\",\"endTime\":\"11:30\"}]}]}"));
        StructuredPlanner planner = new StructuredPlanner(client, new ObjectMapper());
        StructuredPlanner.NewTripParse parse = planner.planNewTrip(city, draft, "两日游");
        assertTrue(parse.ok());
        assertEquals(1, client.calls(), "部分有效直接采用，不重试");
        assertFalse(parse.warnings().isEmpty());
    }

    @Test
    void bothAttemptsFailReturnsFailedParseForRuleFallback() {
        StubLlmClient client = new StubLlmClient(List.of("坏输出一", "坏输出二"));
        StructuredPlanner planner = new StructuredPlanner(client, new ObjectMapper());
        StructuredPlanner.NewTripParse parse = planner.planNewTrip(city, draft, "两日游");
        assertTrue(parse == null || !parse.ok(), "两次都失败 → null/!ok → PlannerAgent 回落规则路径");
        assertEquals(2, client.calls(), "最多重试一次，不会无限循环");
    }
}
