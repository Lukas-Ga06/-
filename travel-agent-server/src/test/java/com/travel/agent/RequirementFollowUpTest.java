package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-01 端到端：缺关键信息时先问，而不是拿默认值直接猜。
 *
 * <p>覆盖四条路径：
 * <ol>
 *   <li><b>合并追问</b>：「帮我规划行程」→ 一次问齐目的地/天数/人数，不产出方案；</li>
 *   <li><b>跨轮补槽</b>：「上海」→「三天」→「两个人」分轮补齐后 → 正常出方案；</li>
 *   <li><b>一次成槽</b>：「三个人下周六去北京两天」→ 直接规划，预算缺省值写进回复；</li>
 *   <li><b>拒绝回答</b>：「你决定吧」→ 用默认值继续，但回复里明示兜底项。</li>
 * </ol>
 */
class RequirementFollowUpTest extends IntegrationTestSupport {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private TripService tripService;

    private AgentDtos.AgentResult say(Account account, String conversationId, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setConversationId(conversationId);
        ctx.setMessage(message);
        return agentEngine.handle(ctx);
    }

    /** 确认并取回落库行程（blocked 方案也显式接受风险，保证测试聚焦在人数口径上） */
    private Trip confirm(Account account, AgentDtos.AgentResult plan) {
        assertThat(plan.getConfirmation()).isNotNull();
        AgentDtos.AgentResult done = agentEngine.confirm(plan.getPlanId(), account.id(),
                plan.getConfirmation().getContentHash(), Boolean.TRUE);
        assertThat(done.getStatus()).as("确认失败: " + done.getMessage())
                .isEqualTo(AgentDtos.STATUS_COMPLETED);
        return tripService.getOwned(done.getTripId(), account.id());
    }

    private static String conversation() {
        return "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    @DisplayName("「帮我规划行程」先追问目的地/天数/人数，不产出方案")
    void asksBeforePlanning() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = say(account, conversation(), "帮我规划行程");

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getPlanId()).isNull();
        assertThat(result.getMissingSlots()).containsExactly("destination", "days", "partySize");
        assertThat(result.getMessage())
                .contains("去哪里").contains("玩几天").contains("几个人");
    }

    @Test
    @DisplayName("跨轮补槽：上海 → 三天 → 两个人，最终按 2 人出方案")
    void collectsSlotsAcrossTurns() throws Exception {
        Account account = registerNew();
        String conversationId = conversation();

        AgentDtos.AgentResult first = say(account, conversationId, "帮我规划上海行程");
        assertThat(first.getMissingSlots()).containsExactly("days", "partySize");

        Thread.sleep(5); // 会话消息按 created_at 排序，避免同一毫秒乱序
        AgentDtos.AgentResult second = say(account, conversationId, "三天");
        assertThat(second.getMissingSlots()).containsExactly("partySize");

        Thread.sleep(5);
        AgentDtos.AgentResult third = say(account, conversationId, "两个人");

        assertThat(third.getStatus()).as(third.getMessage()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(third.getMissingSlots()).isNullOrEmpty();
        Trip trip = confirm(account, third);
        assertThat(trip.getDestination()).isEqualTo("上海");
        assertThat(trip.getPartySize()).isEqualTo(2);
        assertThat(trip.totalDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("一次成槽：三个人下周六去北京两天 → 直接规划，预算兜底明示")
    void plansInOneShot() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = say(account, conversation(), "三个人下周六去北京两天");

        assertThat(result.getStatus()).as(result.getMessage()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(result.getMissingSlots()).isNullOrEmpty();
        assertThat(result.getMessage()).contains("3 人").contains("预算按 ¥5000");
        Trip trip = confirm(account, result);
        assertThat(trip.getDestination()).isEqualTo("北京");
        assertThat(trip.getPartySize()).isEqualTo(3);
        assertThat(trip.totalDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("拒绝回答：追问后说「你决定吧」→ 用默认值继续，并把兜底项写进回复")
    void fallsBackWhenUserDeclines() throws Exception {
        Account account = registerNew();
        String conversationId = conversation();

        AgentDtos.AgentResult ask = say(account, conversationId, "帮我规划杭州行程");
        assertThat(ask.getMissingSlots()).containsExactly("days", "partySize");

        Thread.sleep(5);
        AgentDtos.AgentResult planned = say(account, conversationId, "你决定吧");

        assertThat(planned.getStatus()).as(planned.getMessage()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(planned.getMessage()).contains("按默认").contains("天数按 3 天").contains("人数按 1 人");
        Trip trip = confirm(account, planned);
        assertThat(trip.getDestination()).isEqualTo("杭州");
        assertThat(trip.getPartySize()).isEqualTo(1);
    }
}