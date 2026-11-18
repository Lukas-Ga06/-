package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.AgentEvent;
import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.IntentAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PlanOperation;
import com.travel.agent.agent.PlannerAgent;
import com.travel.agent.agent.TripDraft;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * V2.2 FR-06 端到端：审核发现问题后，引擎必须真正改变结果。
 *
 * <p>两条路径各一个用例：
 * <ol>
 *   <li><b>可修复</b>：转场时间不可能 → 进入 {@code AgentState.REPLAN}（发 replan 事件、落 REPLAN 任务）
 *       → 修复器后移时间 → 重新审核通过 → Diff 里是修复后的时间；</li>
 *   <li><b>修不好</b>：预算连最便宜的组合都装不下 → {@code review.blocked=true}；
 *       不带 {@code acknowledgeIssues} 的确认被拒（方案不被消费），显式接受风险后才提交成功。</li>
 * </ol>
 *
 * <p>规划器与意图识别用 mock 固定输入（测试 profile 无 LLM，规则路径无法构造不可能转场），
 * 审核、修复、确认门全部走真实实现。
 */
class AgentReplanTest extends IntegrationTestSupport {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private TripService tripService;

    @Autowired
    private TravelCatalogService catalog;

    @MockitoBean
    private IntentAgent intentAgent;

    @MockitoBean
    private PlannerAgent plannerAgent;

    private Trip newTrip(Account account, long budget) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("重规划测试行程");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(budget));
        return tripService.create(account.id(), request);
    }

    private AgentContext context(Account account, Trip trip) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setTripId(trip.getId());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage("帮我重新规划上海行程");
        return ctx;
    }

    private static IntentResult planIntent() {
        return IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .build();
    }

    private static ItineraryItem attraction(Trip trip, String title, String start, String end,
                                            double lat, double lng, int cost) {
        return ItineraryItem.builder()
                .dayIndex(1)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title(title)
                .startTime(trip.getStartDate().atTime(LocalTime.parse(start)))
                .endTime(trip.getStartDate().atTime(LocalTime.parse(end)))
                .latitude(lat)
                .longitude(lng)
                .cost(BigDecimal.valueOf(cost))
                .tags("室内,文化")
                .build();
    }

    private AgentPlan craftedPlan(Trip trip, List<ItineraryItem> drafts, long declaredTotal) {
        List<PlanOperation> operations = new ArrayList<>();
        for (ItineraryItem draft : drafts) {
            operations.add(PlanOperation.builder().type(PlanOperation.ADD).draft(draft)
                    .detail("新增：" + draft.getTitle()).build());
        }
        Map<String, Object> extras = new HashMap<>();
        extras.put("total", BigDecimal.valueOf(declaredTotal));
        extras.put("days", 3);
        return AgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .intent(IntentResult.PLAN_TRIP)
                .tripId(trip.getId())
                .city(catalog.matchCity("上海"))
                .tripDraft(TripDraft.builder()
                        .name("重规划测试行程").destination("上海")
                        .startDate(trip.getStartDate()).days(3).budget(trip.getBudget())
                        .build())
                .operations(operations)
                .focus(AgentDtos.AgentFocus.builder().type("itinerary").day(1).build())
                .extras(extras)
                .build();
    }

    @Test
    @DisplayName("转场不可能 → REPLAN 修复 → 复审通过；Diff 用修复后的时间，replan 事件可观测")
    void impossibleTransferIsRepairedBeforeConfirmation() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account, 3000);
        ItineraryItem first = attraction(trip, "外滩", "10:00", "11:00", 31.23, 121.47, 0);
        ItineraryItem second = attraction(trip, "远郊景点", "11:10", "12:10", 31.40, 121.60, 50);
        String originalStart = second.getStartTime().toString();
        AgentPlan plan = craftedPlan(trip, List.of(first, second), 50);

        when(intentAgent.detect(any(), any())).thenReturn(planIntent());
        when(plannerAgent.plan(any(), any(), any())).thenReturn(plan);

        AgentContext ctx = context(account, trip);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ctx.addListener(events::add);
        AgentDtos.AgentResult result = agentEngine.handle(ctx);

        // 重规划真的发生了：REPLAN 事件 + 修复动作 + 复审通过
        assertThat(events).extracting(AgentEvent::event).contains("replan");
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(result.getReview()).isNotNull();
        assertThat(result.getReview().isPassed()).isTrue();
        assertThat(result.getReview().isBlocked()).isFalse();
        assertThat(result.getReview().getAttempts()).isEqualTo(1);
        assertThat(result.getReview().getRepairs()).anyMatch(action -> action.contains("后移"));
        assertThat(result.getMessage()).contains("已自动重规划");
        assertThat(result.getConfirmation().isRequiresAcknowledgement()).isFalse();

        // Diff 展示的是修复后的时间（用户确认的就是最后要提交的内容）
        AgentDtos.DiffChange shifted = result.getDiff().getChanges().stream()
                .filter(change -> "远郊景点".equals(change.getTitle()))
                .findFirst().orElseThrow();
        assertThat(shifted.getStartTime()).isNotEqualTo(originalStart);

        // 审核通过时确认不需要风险确认，可正常提交
        AgentDtos.AgentResult committed = agentEngine.confirm(result.getPlanId(), account.id(), null, null);
        assertThat(committed.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("修不好（预算装不下）→ blocked；不带 acknowledgeIssues 的确认被拒，带后才提交")
    void unresolvedHighRiskBlocksConfirmationUntilAcknowledged() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account, 1); // 预算 ¥1：任何组合都装不下
        ItineraryItem draft = attraction(trip, "外滩", "10:00", "11:30", 31.23, 121.47, 5);
        AgentPlan plan = craftedPlan(trip, List.of(draft), 9999);

        when(intentAgent.detect(any(), any())).thenReturn(planIntent());
        when(plannerAgent.plan(any(), any(), any())).thenReturn(plan);

        AgentDtos.AgentResult result = agentEngine.handle(context(account, trip));

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(result.getReview().isBlocked()).isTrue();
        assertThat(result.getReview().isPassed()).isFalse();
        assertThat(result.getReview().getIssues()).anySatisfy(issue -> {
            assertThat(issue.getType()).isEqualTo("BUDGET_OVERRUN");
            assertThat(issue.getSeverity()).isEqualTo("HIGH");
        });
        assertThat(result.getConfirmation().isRequiresAcknowledgement()).isTrue();
        assertThat(result.getConfirmation().getRiskLevel()).isEqualTo("HIGH");
        String planId = result.getPlanId();

        // 1) 不带显式接受：拒绝提交，且方案不被消费（可改带 ack 重试）
        AgentDtos.AgentResult rejected = agentEngine.confirm(planId, account.id(), null, null);
        assertThat(rejected.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(rejected.getMessage()).contains("高风险").contains("我已了解风险");
        AgentDtos.AgentResult pending = agentEngine.pendingPreview(planId, account.id());
        assertThat(pending.getReview()).isNotNull();
        assertThat(pending.getReview().isBlocked()).isTrue();

        // 2) 显式接受风险后：正常提交
        AgentDtos.AgentResult committed = agentEngine.confirm(planId, account.id(), null, true);
        assertThat(committed.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
    }
}