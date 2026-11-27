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
    @DisplayName("「明天下午有空吗」识别为空闲查询，不写日程")
    void freeTimeQuestionIsNotScheduled() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "明天下午有空吗");

        assertThat(result.getIntent()).isEqualTo(IntentResult.QUERY_FREE_TIME);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).contains("08:00–22:00 的空闲：08:00–22:00");
        assertThat(result.getMessage()).contains("建议 08:00–09:00（1 小时）开会。");
        assertThat(result.getMessage()).contains("回复「安排明天10点开会」即可创建日程。");
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).isEmpty();
    }

    @Test
    @DisplayName("「下周哪些时间空闲」识别为空闲查询")
    void nextWeekFreeTimeQuestionIsRecognized() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "下周哪些时间空闲");

        assertThat(result.getIntent()).isEqualTo(IntentResult.QUERY_FREE_TIME);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).contains("每天 08:00–22:00 的空闲");
        assertThat(result.getMessage()).contains("• ");
        assertThat(result.getMessage()).contains("回复「安排");
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).isEmpty();
    }

    @Test
    @DisplayName("回归：「提醒我明天清空购物车」是备忘，不误判为清空日程")
    void remindToClearShoppingCartIsMemoNotScheduleClear() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "提醒我明天清空购物车");

        assertThat(result.getIntent()).isEqualTo(IntentResult.ADD_MEMO);
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).isEmpty();
    }

    @Test
    @DisplayName("「明天清空所有日程」「删掉明天的所有提醒」仍是清空日程")
    void clearScheduleObjectStillRecognized() throws Exception {
        Account account = registerNew();

        assertThat(run(account, "明天清空所有日程").getIntent()).isEqualTo(IntentResult.CLEAR_SCHEDULE);
        assertThat(run(account, "删掉明天的所有提醒").getIntent()).isEqualTo(IntentResult.CLEAR_SCHEDULE);
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).isEmpty();
    }

    @Test
    @DisplayName("「去西湖哪些时间去人少」是知识问答，不误判为空闲查询")
    void whereToAvoidCrowdsIsKnowledgeNotFreeTime() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "去西湖哪些时间去人少");

        assertThat(result.getIntent()).isEqualTo(IntentResult.QUERY_KNOWLEDGE);
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).isEmpty();
    }

    @Test
    @DisplayName("旅行规划意图不受影响")
    void planIntentStillWorks() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "帮我规划上海三日游，2人，预算3000元");

        assertThat(result.getIntent()).isEqualTo(IntentResult.PLAN_TRIP);
    }

    @Test
    @DisplayName("回归：「报销这张发票」识别为 RECORD_INVOICE（没有发票照片时如实说没找到）")
    void invoiceReimbursementIsRecognized() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "报销这张发票");

        assertThat(result.getIntent()).isEqualTo(IntentResult.RECORD_INVOICE);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).contains("没有找到已上传的发票照片");
        assertThat(result.getConfirmation()).isNull();
    }

    @Test
    @DisplayName("回归：「记一下明天办签证」仍是 ADD_MEMO，不会被发票/记账意图抢走")
    void memoIsNotStolenByInvoiceIntent() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "记一下明天办签证");

        assertThat(result.getIntent()).isEqualTo(IntentResult.ADD_MEMO);
    }
}
