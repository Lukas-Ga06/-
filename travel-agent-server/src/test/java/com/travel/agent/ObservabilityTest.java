package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可观测性：探活端点、请求链路标识、指标注册。
 * 有这些之后"线上报错"才能被定位，而不是只能靠时间戳猜。
 */
class ObservabilityTest extends IntegrationTestSupport {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private LlmClient llmClient;

    @Test
    @DisplayName("健康检查无需登录即可访问")
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("业务接口仍然需要登录（actuator 之外的路径不能顺带放行）")
    void actuatorWhitelistDoesNotOpenApi() throws Exception {
        mockMvc.perform(get("/api/trips")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("每个请求都带 X-Trace-Id，且上游传入的 id 会被沿用")
    void traceIdIsReturnedAndPropagated() throws Exception {
        String generated = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");
        assertThat(generated).isNotBlank();

        String forwarded = mockMvc.perform(get("/actuator/health").header("X-Trace-Id", "trace-from-gateway-01"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");
        assertThat(forwarded).isEqualTo("trace-from-gateway-01");
    }

    @Test
    @DisplayName("LLM 降级会被计数（未配置 key 时记 skipped）")
    void llmDegradeIsCounted() {
        // 测试 profile 关闭了 LLM：调用一次即应产生 result=skipped 计数
        llmClient.chat(List.of(LlmMessage.user("你好")), 0.0);

        double skipped = meterRegistry.get("llm.calls").tag("result", "skipped").counter().count();
        assertThat(skipped).isGreaterThan(0);
    }
}
