package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentBudget;
import com.travel.agent.config.AgentBudgetProperties;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第二阶段验收：{@code travel.agent.budget.spend.*} 的配置绑定与「花费到顶」的真实请求行为。
 *
 * <p>为什么需要这个测试：单测里 {@link AgentBudget} 是手工构造的，能证明语义正确，
 * 但证明不了「线上配置真的进了预算对象」，也证明不了「工具花费触顶时整轮请求仍然产出可用结果」。
 * 这里用真实 Spring 上下文 + 真实规则规划链路，把工具次数上限压到 2（远低于一次规划的真实用量），
 * 断言：<b>不抛异常、不把状态打成 FAILED、回复里如实说明「花费预算耗尽、工具没执行」</b>。
 */
@SpringBootTest(properties = {
        "travel.agent.budget.enabled=true",
        "travel.agent.budget.total-ms=30000",
        "travel.agent.budget.spend.enabled=true",
        // 一次三日游规划真实用量约 20+ 次工具：上限 2 保证必然触顶，且不影响 LLM/时间预算
        "travel.agent.budget.spend.max-tool-calls=2"
})
class AgentSpendIntegrationTest extends IntegrationTestSupport {

    private static final String PLAN_MESSAGE = "帮我规划上海三日游，2人，预算3000元";

    @Autowired
    private AgentBudgetProperties budgetProperties;

    @Autowired
    private LlmProperties llmProperties;

    @Test
    @DisplayName("嵌套配置绑定：spend 的上限真的进了本轮预算实例")
    void spendCapsAreBoundIntoBudget() {
        AgentBudget budget = budgetProperties.newBudget(llmProperties);
        assertThat(budget.isEnabled()).isTrue();
        assertThat(budget.spend().isEnabled()).isTrue();
        assertThat(budget.spend().summary())
                .as("写入配置的 tool 上限必须出现在预算摘要里（写了没人读等于没配）")
                .contains("tool=0/2");
    }

    @Test
    @DisplayName("工具花费触顶：请求仍返回可用结果，reply/warnings 如实写明花费预算耗尽")
    void toolSpendExhaustionDegradesInsteadOfFailing() throws Exception {
        Account account = registerNew();

        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + PLAN_MESSAGE + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");

        assertThat(data.path("status").asText())
                .as("花费到顶是降级，不是执行失败；绝不能把用户请求打成 FAILED")
                .isNotEqualTo("FAILED")
                .isNotEqualTo("ERROR");
        String visible = data.path("message").asText() + " | " + data.path("warnings");
        assertThat(visible)
                .as("ADR-002：可以少做，但必须如实说明「花费预算」与「工具未执行」")
                .contains("花费预算")
                .contains("工具调用");
    }
}