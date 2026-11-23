package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 运行能力自检接口：让「有没有模型 / 联网走哪条路」可见，且不泄露密钥 */
class SystemStatusTest extends IntegrationTestSupport {

    @Test
    @DisplayName("返回模型与定位能力状态，且不包含任何密钥")
    void reportsCapabilitiesWithoutSecrets() throws Exception {
        Account account = registerNew();

        MvcResult result = mockMvc.perform(get("/api/system/status").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.llmConfigured").value(false))
                // P5：配置态与最近调用态分离（测试 profile 没有 Key，两者都必须是 false）
                .andExpect(jsonPath("$.data.llmVerified").value(false))
                .andExpect(jsonPath("$.data.geoProvider").value("osm"))
                // P2：没配高德时路线来源必须是 estimated，且新增的自报字段口径一致
                .andExpect(jsonPath("$.data.routeProvider").value("estimated"))
                .andExpect(jsonPath("$.data.routeConfigured").value(false))
                .andExpect(jsonPath("$.data.amapVerified").value(false))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                // P0-A：MCP 接入状态与模型探测字段必须存在，且口径不伪装（LLM 未配置时模型可用性为 null）
                .andExpect(jsonPath("$.data.mcpEnabled").isBoolean())
                .andExpect(jsonPath("$.data.mcpConnected").isBoolean())
                .andExpect(jsonPath("$.data.mcpToolCount").isNumber())
                .andExpect(jsonPath("$.data.amapMcpToolCount").isNumber())
                .andExpect(jsonPath("$.data.mcpServers").isArray())
                .andExpect(jsonPath("$.data.mcpHint").isString())
                .andExpect(jsonPath("$.data.llmProvider").value(nullValue()))
                .andExpect(jsonPath("$.data.llmModelAvailable").value(nullValue()))
                .andExpect(jsonPath("$.data.llmModelCheckPending").value(false))
                // 天气自报（P0）：测试 profile 只跑模拟天气，状态接口必须如实说「模拟」而不是真实预报
                .andExpect(jsonPath("$.data.weatherProvider").value("simulated"))
                .andExpect(jsonPath("$.data.weatherLastError").value(nullValue()))
                .andExpect(jsonPath("$.data.weatherHint").value(org.hamcrest.Matchers.containsString("模拟天气")))
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("api-key", "apiKey", "sk-");
    }
}
