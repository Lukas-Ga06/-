package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 列表接口的 limit/offset 生效（响应体积有上限，不再全量返回） */
class PagingApiTest extends IntegrationTestSupport {

    private void createTrip(Account account, String name) throws Exception {
        mockMvc.perform(post("/api/trips")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"destination\":\"上海\"}".formatted(name)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("行程列表支持 limit 与 offset")
    void tripListSupportsPaging() throws Exception {
        Account account = registerNew();
        createTrip(account, "行程A");
        createTrip(account, "行程B");
        createTrip(account, "行程C");

        mockMvc.perform(get("/api/trips").param("limit", "2").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/trips").param("limit", "2").param("offset", "2")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/trips").param("offset", "50").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("limit 超过上限会被收敛而不是报错")
    void limitIsClampedNotRejected() throws Exception {
        Account account = registerNew();
        createTrip(account, "行程A");

        MvcResult result = mockMvc.perform(get("/api/trips").param("limit", "999999")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(body(result).path("data").size()).isEqualTo(1);
    }
}
