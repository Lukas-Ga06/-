package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图路由精度：随口提到的会议不能被写成日程；真正的排期请求要能识别。
 * 回归背景：旧实现里 scheduleAdd 只要求关键词 + parseScheduleRequest 非 null，
 * 而后者对任何非空文本都返回非 null，"今天开会好累"会被真的创建成日程。
 */
class IntentRoutingTest extends IntegrationTestSupport {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private ScheduleItemRepository scheduleRepository;

    private AgentDtos.AgentResult run(Account account, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage(message);
        return agentEngine.handle(ctx);
    }

    @Test
    @DisplayName("「今天开会好累」不会被写成日程，而是给出能力提示")
    void casualMentionOfMeetingIsNotScheduled() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "今天开会好累");

        assertThat(result.getIntent()).isEqualTo(IntentResult.CHITCHAT);
        List<ScheduleItem> schedule = scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id());
        assertThat(schedule).isEmpty();
    }

    @Test
    @DisplayName("带明确时间的排期请求仍会创建日程")
    void explicitScheduleRequestIsStillHandled() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "明天下午3点开评审会");

        assertThat(result.getIntent()).isEqualTo(IntentResult.ADD_SCHEDULE);
        List<ScheduleItem> schedule = scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id());
        assertThat(schedule).hasSize(1);
        assertThat(schedule.get(0).getTitle()).contains("评审会");
        assertThat(schedule.get(0).getTime()).hasToString("15:00");
    }

    @Test
    @DisplayName("旅行规划意图不受影响")
    void planIntentStillWorks() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "帮我规划上海三日游，预算3000元");

        assertThat(result.getIntent()).isEqualTo(IntentResult.PLAN_TRIP);
    }
}
