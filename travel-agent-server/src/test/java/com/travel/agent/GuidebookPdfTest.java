package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 路书导出：PDF 二进制、越权拒绝 */
class GuidebookPdfTest extends IntegrationTestSupport {

    @Test
    void ownerCanExportPdfWithDaysAndBudget() throws Exception {
        Account owner = registerNew();
        long tripId = createTrip(owner);

        addItem(owner, tripId, """
                {"dayIndex":1,"itemType":"attraction","title":"故宫博物院","cost":60,"description":"周一闭馆，需提前预约"}
                """);
        addItem(owner, tripId, """
                {"dayIndex":2,"itemType":"restaurant","title":"全聚德烤鸭","cost":180,"description":"人均 180 元，建议错峰"}
                """);

        MvcResult result = mockMvc.perform(get("/api/trips/" + tripId + "/guidebook.pdf")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertTrue(pdf.length > 2000, "PDF 内容过短: " + pdf.length);
        assertTrue(pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F', "响应不是 PDF");
    }

    @Test
    void otherUserCannotExport() throws Exception {
        Account owner = registerNew();
        long tripId = createTrip(owner);
        Account stranger = registerNew();
        mockMvc.perform(get("/api/trips/" + tripId + "/guidebook.pdf")
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
    }

    private long createTrip(Account account) throws Exception {
        String body = """
                {"name":"北京三日游","destination":"北京","startDate":"2026-11-01","endDate":"2026-11-03","budget":3000}
                """;
        return body(mockMvc.perform(post("/api/trips")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asLong();
    }

    private void addItem(Account account, long tripId, String body) throws Exception {
        mockMvc.perform(post("/api/trips/" + tripId + "/itinerary")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
