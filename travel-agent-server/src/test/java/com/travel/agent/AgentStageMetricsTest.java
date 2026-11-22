package com.travel.agent;

import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段计时指标 agent.stage{stage=intent|plan|tool|commit}。
 *
 * <p>用 mock 的 {@link IntentAgent} 把一次测试内的请求分别导到四条路径：
 * 规划（intent+plan）、外部工具（tool）、确认提交（commit，走 POST /api/agent/confirm 的
 * {@code AgentEngine.confirm}），最后用抛异常的意图识别覆盖"失败也要计时"。
 *
 * <p>测试 profile 不配置真实 LLM，规划走规则路径，全程离线。
 */
class AgentStageMetricsTest extends IntegrationTestSupport {

    private static final String STAGE_METRIC = "agent.stage";
    private static final Pattern PLAN_ID = Pattern.compile("\"planId\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AgentEngine agentEngine;

    @MockitoBean
    private IntentAgent intentAgent;

    /** 某阶段已记录的时间样本数；指标还不存在时算 0 */
    private long stageCount(String stage) {
        Timer timer = meterRegistry.find(STAGE_METRIC).tag("stage", stage).timer();
        return timer == null ? 0L : timer.count();
    }

    /** GET /api/agent/stream 跑到结束，返回 SSE 文本 */
    private String stream(Account account, String message) throws Exception {
        return sseDispatch(get("/api/agent/stream")
                .header("Authorization", account.bearer())
                .param("message", message))
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("intent/plan/tool/commit 四个阶段都有 agent.stage 计时，异常路径也计数")
    void allStagesAreTimedIncludingFailures() throws Exception {
        Account account = registerNew();

        // 1) intent + plan：规划新行程 → 产出待确认方案（planId 从 SSE 的 complete 事件里取）
        when(intentAgent.detect(any(), any())).thenReturn(IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .build());
        String planBody = stream(account, "帮我规划上海三日游，2人，预算3000元");
        Matcher matcher = PLAN_ID.matcher(planBody);
        assertThat(matcher.find()).as("规划应产出待确认方案，SSE 文本: " + planBody).isTrue();
        String planId = matcher.group(1);
        assertThat(stageCount("intent")).isGreaterThanOrEqualTo(1L);
        assertThat(stageCount("plan")).isGreaterThanOrEqualTo(1L);

        // 2) tool：INVOKE_TOOL 命中已注册的只读工具
        when(intentAgent.detect(any(), any())).thenReturn(IntentResult.builder()
                .intent(IntentResult.INVOKE_TOOL)
                .toolName("search_attraction")
                .toolArgs(Map.of("city", "上海"))
                .build());
        stream(account, "查一下上海的景点");
        assertThat(stageCount("tool")).isGreaterThanOrEqualTo(1L);

        // 3) commit：POST /api/agent/confirm 的落库阶段（本类内 AgentEngine.confirm 计时）
        long commitBefore = stageCount("commit");
        AgentDtos.AgentResult committed = agentEngine.confirm(planId, account.id());
        assertThat(committed.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(stageCount("commit")).isGreaterThan(commitBefore);

        // 4) 异常路径：意图识别抛异常，intent 阶段照样要落一个样本
        long intentBefore = stageCount("intent");
        when(intentAgent.detect(any(), any())).thenThrow(new IllegalStateException("mock intent failure"));
        String failedBody = stream(account, "随便聊聊");
        assertThat(failedBody).as("异常被兜底成用户可读提示").contains("抱歉");
        assertThat(stageCount("intent")).isGreaterThan(intentBefore);

        // 四个 stage 至少各出现一次
        assertThat(meterRegistry.find(STAGE_METRIC).timers())
                .extracting(timer -> timer.getId().getTag("stage"))
                .contains("intent", "plan", "tool", "commit");
    }
}