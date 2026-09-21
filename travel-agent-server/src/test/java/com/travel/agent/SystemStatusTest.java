package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.data.geoProvider").value("osm"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("api-key", "apiKey", "sk-");
    }
}
