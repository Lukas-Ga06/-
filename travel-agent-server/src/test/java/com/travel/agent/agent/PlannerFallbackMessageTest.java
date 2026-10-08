package com.travel.agent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 目录外城市的兜底说明：原因要能指导下一步动作 */
class PlannerFallbackMessageTest {

    private static final String SUPPORTED = "上海、北京、杭州、石家庄";

    @Test
    @DisplayName("没配模型时直接点明 LLM_API_KEY，而不是笼统说联网失败")
    void withoutModelPointsAtApiKey() {
        String message = PlannerAgent.unsupportedCityMessage("海口", SUPPORTED, false);

        assertThat(message)
                .contains("海口")
                .contains("LLM_API_KEY")
                .doesNotContain("联网查询也未成功");
    }

    @Test
    @DisplayName("配了模型则说明是联网也没找到，并仍然列出已开通城市")
    void withModelSaysOnlineLookupFailed() {
        String message = PlannerAgent.unsupportedCityMessage("海口", SUPPORTED, true);

        assertThat(message)
                .contains("联网查询也没找到")
                .contains(SUPPORTED);
    }
}
