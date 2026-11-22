package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.AgentEvent;
import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PlannerAgent;
import com.travel.agent.agent.RequirementSlots;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.MemoService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 旅行闭环：行程项状态、旅行事件、回顾聚合、足迹口径、行中锁定与 RAG 证据。
 *
 * <p>这些断言钉死的是产品口径而不是实现细节：
 * <ul>
 *   <li>「去过」只认 visitedAt，未来的计划不能被渲染成已到访；</li>
 *   <li>完成率、计划成本、实际支出三套口径不互相覆盖；</li>
 *   <li>已开始/已完成的条目不能被天气驱动的自动重规划覆盖；</li>
 *   <li>方案里的知识引用带 documentId/version/chunkId/score/source。</li>
 * </ul>
 */
class TripClosedLoopTest extends IntegrationTestSupport {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private PlannerAgent plannerAgent;

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private TripService tripService;

    @Autowired
    private MemoService memoService;

    @Autowired
    private ScheduleService scheduleService;

    private long createTrip(Account account, LocalDate start, int days) throws Exception {
        String body = """
                {"name":"闭环测试行程","destination":"上海","startDate":"%s","endDate":"%s","budget":3000,"partySize":2}
                """.formatted(start, start.plusDays(days - 1L));
        JsonNode data = body(mockMvc.perform(post("/api/trips")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        return data.path("id").asLong();
    }

    private long addItem(Account account, long tripId, String title, String tags,
                         double lat, double lng, int cost) throws Exception {
        String body = """
                {"dayIndex":1,"itemType":"attraction","title":"%s","startTime":"10:00","endTime":"12:00",
                 "latitude":%s,"longitude":%s,"cost":%d,"tags":["%s"]}
                """.formatted(title, lat, lng, cost, tags);
        JsonNode data = body(mockMvc.perform(post("/api/trips/" + tripId + "/itinerary")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        return data.path("id").asLong();
    }

    private AgentContext context(Account account, Long tripId, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setTripId(tripId);
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage(message);
        return ctx;
    }

    @Test
    @DisplayName("打卡完成：写 visitedAt + CHECK_IN 事件 + 回顾区分计划/实际，撤销可还原状态")
    void completedItemProducesVisitedFactAndReview() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().minusDays(1);
        long tripId = createTrip(account, start, 2);
        long museum = addItem(account, tripId, "上海博物馆", "室内", 31.2304, 121.4737, 50);
        addItem(account, tripId, "外滩", "室外", 31.2397, 121.4900, 0);

        mockMvc.perform(put("/api/itinerary/" + museum + "/status")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.visitedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());

        // 实际支出：计划 ¥50 vs 实际 ¥60 → variance=10，口径不互相覆盖
        mockMvc.perform(post("/api/trips/" + tripId + "/expenses")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"餐饮\",\"amount\":60,\"spentAt\":\"%s\"}".formatted(start)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/trips/" + tripId + "/review")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.completedItems").value(1))
                .andExpect(jsonPath("$.data.completionRate").value(0.5))
                .andExpect(jsonPath("$.data.plannedCost").value(50))
                .andExpect(jsonPath("$.data.actualCost").value(60))
                .andExpect(jsonPath("$.data.variance").value(10))
                .andExpect(jsonPath("$.data.plannedPlaces.length()").value(2))
                .andExpect(jsonPath("$.data.visitedPlaces.length()").value(1))
                .andExpect(jsonPath("$.data.visitedPlaces[0].title").value("上海博物馆"))
                .andExpect(jsonPath("$.data.visitedPlaces[0].visitedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.actualRoute.length()").value(1))
                // 外滩未到访：计划里有它，但实际路线里绝不能出现
                .andExpect(jsonPath("$.data.actualRoute[0].title").value("上海博物馆"))
                .andExpect(jsonPath("$.data.modifications").isNotEmpty())
                .andExpect(jsonPath("$.data.aiSummary").isNotEmpty());

        mockMvc.perform(get("/api/trips/" + tripId + "/events")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("CHECK_IN"))
                .andExpect(jsonPath("$.data[0].source").value("user"));

        // 足迹：visited=true 只因为真的打了卡；plannedPlaces 仍然包含未到访的外滩
        mockMvc.perform(get("/api/trips/map").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].visited").value(true))
                .andExpect(jsonPath("$.data[0].plannedPlaces").value(2))
                .andExpect(jsonPath("$.data[0].visitedPlaces").value(1))
                .andExpect(jsonPath("$.data[0].actualRoute.length()").value(1));

        // 撤销状态变化：快照含 status，还原后回到 PENDING 且 visitedAt 清空
        mockMvc.perform(post("/api/agent/undo")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":%d}".formatted(tripId)))
                .andExpect(status().isOk());
        ItineraryItem restored = itineraryService.findById(museum);
        assertThat(restored.getStatus()).isEqualTo(ItineraryItem.STATUS_PENDING);
        assertThat(restored.getVisitedAt()).isNull();
    }

    @Test
    @DisplayName("行中锁定：已完成的户外景点不会被「明天下雨」自动替换")
    void completedOutdoorItemIsNotAutoReplaced() throws Exception {
        Account account = registerNew();
        LocalDate start = LocalDate.now().plusDays(1);
        long tripId = createTrip(account, start, 2);
        long bund = addItem(account, tripId, "外滩", "室外", 31.2397, 121.4900, 0);
        itineraryService.updateStatus(tripService.getOwned(tripId, account.id()), bund, ItineraryItem.STATUS_COMPLETED);

        AgentDtos.AgentResult result = agentEngine.handle(context(account, tripId, "明天下雨，把户外景点换掉"));

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).contains("未开始").contains("已完成");
        // 没有产出可确认的操作计划 → 不会走到确认门
        assertThat(result.getDiff()).isNull();
    }

    @Test
    @DisplayName("规划期 RAG：方案 extras 带 documentId/version/chunkId/score/source 引用并发出 rag_result")
    void plannerAttachesKnowledgeCitations() {
        Account account = new Account(1L, "planner-rag", null);
        String message = "帮我规划上海三日游，预算3000元，亲子友好";
        AgentContext ctx = context(account, null, message);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ctx.addListener(events::add);
        IntentResult intent = IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .slots(RequirementSlots.fromMessage(message))
                .build();

        AgentPlan plan = plannerAgent.plan(ctx, intent, null);

        assertThat(plan).isNotNull();
        assertThat(events).extracting(AgentEvent::event).contains("rag_result");
        @SuppressWarnings("unchecked")
        List<com.travel.agent.rag.RagCitation> citations =
                (List<com.travel.agent.rag.RagCitation>) plan.getExtras().get("citations");
        assertThat(citations).isNotEmpty();
        assertThat(citations).allSatisfy(citation -> {
            assertThat(citation.documentId()).isNotNull();
            assertThat(citation.chunkId()).isNotNull();
            assertThat(citation.score()).isGreaterThan(0);
            assertThat(citation.title()).isNotBlank();
            assertThat(citation.snippet()).isNotBlank();
        });
    }

    @Test
    @DisplayName("旅行关联：行程内添加的备忘/日程写入 tripId，回顾按行程计数")
    void tripScopedMemoAndScheduleAreLinked() throws Exception {
        Account account = registerNew();
        long tripId = createTrip(account, LocalDate.now().plusDays(1), 3);

        AgentDtos.AgentResult memoResult = agentEngine.handle(context(account, tripId, "记一下明天办签证"));
        AgentDtos.AgentResult scheduleResult = agentEngine.handle(context(account, tripId, "明天下午3点加入日程办签证"));
        assertThat(memoResult.getIntent()).isEqualTo(IntentResult.ADD_MEMO);
        assertThat(scheduleResult.getIntent()).isEqualTo(IntentResult.ADD_SCHEDULE);

        List<Memo> memos = memoService.list(account.id());
        assertThat(memos).anySatisfy(memo -> {
            assertThat(memo.getTripId()).isEqualTo(tripId);
            assertThat(memo.getContent()).contains("签证");
        });
        List<ScheduleItem> schedules = scheduleService.list(account.id());
        assertThat(schedules).anySatisfy(item -> {
            assertThat(item.getTripId()).isEqualTo(tripId);
            assertThat(item.getSource()).isEqualTo("AGENT");
        });

        mockMvc.perform(get("/api/trips/" + tripId + "/review")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memoCount").value(1))
                .andExpect(jsonPath("$.data.scheduleCount").value(1));
    }
}
