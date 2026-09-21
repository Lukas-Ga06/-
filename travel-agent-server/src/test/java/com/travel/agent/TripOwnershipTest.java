package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 多租户隔离：A 用户的行程不能被 B 用户读取或删除 */
class TripOwnershipTest extends IntegrationTestSupport {

    private long createTrip(Account owner) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/trips")
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试行程\",\"destination\":\"上海\",\"budget\":3000}"))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("本人可以读取自己的行程")
    void ownerCanReadOwnTrip() throws Exception {
        Account owner = registerNew();
        long tripId = createTrip(owner);

        mockMvc.perform(get("/api/trips/" + tripId).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) tripId));
    }

    @Test
    @DisplayName("他人读取行程返回 404 业务码，不泄露行程是否存在")
    void otherUserCannotReadTrip() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        long tripId = createTrip(owner);

        mockMvc.perform(get("/api/trips/" + tripId).header("Authorization", intruder.bearer()))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("他人删除行程失败，且原行程仍然存在")
    void otherUserCannotDeleteTrip() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        long tripId = createTrip(owner);

        mockMvc.perform(delete("/api/trips/" + tripId).header("Authorization", intruder.bearer()))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/trips/" + tripId).header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
    }
}
