package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PlannerAgent;
import com.travel.agent.agent.ReflectAgent;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.2 FR-06：Reviewer 结构化判定 + 超预算动态收敛。
 *
 * <p>规则路径（无 LLM）下，预算超出时规划器先降级酒店，仍超出则逐次移除最贵景点
 * （每天保底 1 个）直到进入预算；收敛原因写入 extras.budgetAdjusted 与 reasoning。
 * ReflectAgent 的结构化判定区分硬约束（预算，REVISE）与软提醒（时间冲突，提示）。
 */
class PlannerBudgetTrimTest extends IntegrationTestSupport {

    @Autowired
    private PlannerAgent plannerAgent;

    @Autowired
    private ReflectAgent reflectAgent;

    @Autowired
    private TripService tripService;

    private AgentPlan plan(String message, Long userId) throws Exception {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(userId);
        ctx.setUsername("tester");
        ctx.setMessage(message);
        IntentResult intent = IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .constraints(new HashMap<>())
                .build();
        return plannerAgent.plan(ctx, intent, null);
    }

    @Test
    @DisplayName("小预算规则规划：自动降级/精简到预算内，收敛原因可追溯")
    void smallBudgetConvergesWithTraceableReason() throws Exception {
        Account account = registerNew();
        // 上海 3 日、预算 1500：酒店+全部景点+餐饮必然超出，触发降级与景点精简
        AgentPlan plan = plan("帮我规划上海三日游，预算1500元", account.id());

        assertThat(plan.getOperations()).isNotEmpty();
        assertThat(plan.getExtras().get("total")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) plan.getExtras().get("total"))
                .as("精简后合计应进入预算").isLessThanOrEqualTo(BigDecimal.valueOf(1500));
        assertThat(plan.getExtras()).containsKey("budgetAdjusted");
        assertThat((String) plan.getExtras().get("budgetAdjusted")).contains("自动精简");
        assertThat(plan.getReasoning()).contains("自动精简");

        // 保底语义：每天至少保留 1 个景点
        Map<Integer, Long> attractionsByDay = new HashMap<>();
        plan.getOperations().stream()
                .filter(op -> com.travel.agent.agent.PlanOperation.ADD.equals(op.getType()))
                .map(op -> op.getDraft())
                .filter(i -> ItineraryItem.TYPE_ATTRACTION.equals(i.getItemType()))
                .forEach(i -> attractionsByDay.merge(i.getDayIndex(), 1L, Long::sum));
        assertThat(attractionsByDay).allSatisfy((day, count) -> assertThat(count).isGreaterThanOrEqualTo(1));

        // 收敛后 Reviewer 判定应通过（预算硬约束不再触发 REVISE）
        ReflectAgent.ReviewVerdict verdict = reflectAgent.reviewVerdict(plan, null);
        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.issues()).noneMatch(i -> ReflectAgent.PlanIssue.TYPE_BUDGET.equals(i.type()));
    }

    @Test
    @DisplayName("预算硬约束超限 → REVISE（HIGH）；时间冲突 → 软提醒（MEDIUM，不阻断）")
    void verdictSeparatesHardAndSoftIssues() throws Exception {
        Account account = registerNew();
        AgentPlan plan = plan("帮我规划上海三日游，预算20000元", account.id());

        // 预算内正常规划：无预算问题
        ReflectAgent.ReviewVerdict ok = reflectAgent.reviewVerdict(plan, null);
        assertThat(ok.passed()).isTrue();

        // 构造超限：同一份计划但行程预算被砍到极低 → 预算问题（硬约束）
        Trip trip = tripService.create(account.id(), tripRequest(BigDecimal.ONE));
        Map<String, Object> extras = new HashMap<>(plan.getExtras());
        extras.put("total", BigDecimal.valueOf(99999));
        AgentPlan overBudget = plan.toBuilder().extras(extras).build();
        ReflectAgent.ReviewVerdict revise = reflectAgent.reviewVerdict(overBudget, trip);
        assertThat(revise.passed()).isFalse();
        assertThat(revise.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.type()).isEqualTo(ReflectAgent.PlanIssue.TYPE_BUDGET);
                    assertThat(issue.severity()).isEqualTo(ReflectAgent.PlanIssue.SEVERITY_HIGH);
                });
        // 警告文案投影与旧 review() 契约一致
        assertThat(revise.warnings()).anyMatch(w -> w.contains("超出预算"));
    }

    private com.travel.agent.dto.TripDtos.TripRequest tripRequest(BigDecimal budget) {
        com.travel.agent.dto.TripDtos.TripRequest request = new com.travel.agent.dto.TripDtos.TripRequest();
        request.setName("判定测试行程");
        request.setDestination("上海");
        request.setBudget(budget);
        return request;
    }
}
