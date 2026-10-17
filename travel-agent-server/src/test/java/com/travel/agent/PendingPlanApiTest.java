package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PendingPlanStore;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 待确认方案取回接口：刷新页面后还能继续确认，且不能看到别人的方案 */
class PendingPlanApiTest extends IntegrationTestSupport {

    @Autowired
    private PendingPlanStore store;

    private String putPlan(Long userId) {
        String planId = "plan-api-" + userId + "-" + System.nanoTime();
        AgentPlan plan = AgentPlan.builder()
                .planId(planId)
                .intent(IntentResult.MODIFY_ITINERARY)
                .tripId(null)
                .operations(List.of())
                .reasoning("测试：待确认方案")
                .build();
        AgentDtos.AgentResult preview = AgentDtos.AgentResult.builder()
                .planId(planId)
                .status(AgentDtos.STATUS_NEED_CONFIRMATION)
                .message("已生成方案，请确认")
                .confirmation(AgentDtos.ConfirmationRequest.builder()
                        .planId(planId).summary("新增 2 项").riskLevel("MEDIUM").build())
                .build();
        store.put(new PendingPlanStore.PendingPlan(planId, userId, null, plan, preview, null, Instant.now()));
        return planId;
    }

    @Test
    @DisplayName("方案所有者可以取回待确认内容")
    void ownerCanFetchPendingPlan() throws Exception {
        Account account = registerNew();
        String planId = putPlan(account.id());

        mockMvc.perform(get("/api/agent/plans/" + planId).header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value(planId))
                .andExpect(jsonPath("$.data.status").value(AgentDtos.STATUS_NEED_CONFIRMATION))
                .andExpect(jsonPath("$.data.confirmation.summary").value("新增 2 项"));
    }

    @Test
    @DisplayName("他人取不走别人的方案；不存在的方案返回 404")
    void otherUserAndUnknownPlanAreRejected() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        String planId = putPlan(owner.id());

        mockMvc.perform(get("/api/agent/plans/" + planId).header("Authorization", intruder.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/agent/plans/not-exist-plan").header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());

        // 越权访问不能顺手删掉方案
        mockMvc.perform(get("/api/agent/plans/" + planId).header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
    }
}
