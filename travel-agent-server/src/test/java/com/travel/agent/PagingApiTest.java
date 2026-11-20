package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 列表接口分页契约（batch9-contract.md §1）：
 * data 仍是数组，分页信息挂在同级 pagination（total/limit/offset/hasMore）；
 * limit 默认 20、上限 200，offset 负数按 0；未分页接口不输出 pagination 键。
 */
class PagingApiTest extends IntegrationTestSupport {

    private long createTrip(Account account, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/trips")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"destination\":\"上海\"}".formatted(name)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("id").asLong();
    }

    private void createMemo(Account account, String content) throws Exception {
        mockMvc.perform(post("/api/memos")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"%s\"}".formatted(content)))
                .andExpect(status().isOk());
    }

    private void addExpense(Account account, long tripId, String amount) throws Exception {
        mockMvc.perform(post("/api/trips/" + tripId + "/expenses")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"餐饮\",\"amount\":%s,\"spentAt\":\"%s\"}"
                                .formatted(amount, LocalDate.now())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("行程列表支持 limit/offset 并返回 pagination 元数据")
    void tripListSupportsPaging() throws Exception {
        Account account = registerNew();
        createTrip(account, "行程A");
        createTrip(account, "行程B");
        createTrip(account, "行程C");

        mockMvc.perform(get("/api/trips").param("limit", "2").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.total").value(3))
                .andExpect(jsonPath("$.pagination.limit").value(2))
                .andExpect(jsonPath("$.pagination.offset").value(0))
                .andExpect(jsonPath("$.pagination.hasMore").value(true));

        mockMvc.perform(get("/api/trips").param("limit", "2").param("offset", "2")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.pagination.total").value(3))
                .andExpect(jsonPath("$.pagination.offset").value(2))
                .andExpect(jsonPath("$.pagination.hasMore").value(false));

        mockMvc.perform(get("/api/trips").param("offset", "50").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }

    @Test
    @DisplayName("offset 翻页不重叠：两页数据没有重复元素")
    void pagesDoNotOverlap() throws Exception {
        Account account = registerNew();
        createTrip(account, "行程A");
        createTrip(account, "行程B");
        createTrip(account, "行程C");

        Set<Long> firstPage = tripIds(body(mockMvc.perform(get("/api/trips")
                        .param("limit", "2").param("offset", "0")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn()));
        Set<Long> secondPage = tripIds(body(mockMvc.perform(get("/api/trips")
                        .param("limit", "2").param("offset", "2")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk()).andReturn()));

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
    }

    private Set<Long> tripIds(JsonNode response) {
        Set<Long> ids = new LinkedHashSet<>();
        response.path("data").forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    @Test
    @DisplayName("limit 超过上限会被夹到 200 而不是报错")
    void limitIsClampedNotRejected() throws Exception {
        Account account = registerNew();
        createTrip(account, "行程A");

        MvcResult result = mockMvc.perform(get("/api/trips").param("limit", "999999")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.limit").value(200))
                .andReturn();

        assertThat(body(result).path("data").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("offset 负数按 0 处理，limit 缺省为 20")
    void negativeOffsetAndDefaultLimit() throws Exception {
        Account account = registerNew();
        createTrip(account, "行程A");
        createTrip(account, "行程B");

        mockMvc.perform(get("/api/trips").param("offset", "-5").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.offset").value(0))
                .andExpect(jsonPath("$.pagination.limit").value(20))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("未分页的响应不输出 pagination 键")
    void paginationAbsentOnNonPagedResponse() throws Exception {
        Account account = registerNew();
        long tripId = createTrip(account, "行程A");

        mockMvc.perform(get("/api/trips/" + tripId).header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination").doesNotExist());
    }

    @Test
    @DisplayName("备忘列表分页")
    void memoListPagination() throws Exception {
        Account account = registerNew();
        createMemo(account, "备忘A");
        createMemo(account, "备忘B");
        createMemo(account, "备忘C");

        mockMvc.perform(get("/api/memos").param("limit", "2").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.total").value(3))
                .andExpect(jsonPath("$.pagination.hasMore").value(true));

        mockMvc.perform(get("/api/memos").param("limit", "2").param("offset", "2")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }

    @Test
    @DisplayName("行程账单列表分页")
    void expenseListPagination() throws Exception {
        Account account = registerNew();
        long tripId = createTrip(account, "记账测试");
        addExpense(account, tripId, "10.00");
        addExpense(account, tripId, "20.00");
        addExpense(account, tripId, "30.00");

        mockMvc.perform(get("/api/trips/" + tripId + "/expenses").param("limit", "2")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.pagination.total").value(3))
                .andExpect(jsonPath("$.pagination.hasMore").value(true));

        mockMvc.perform(get("/api/trips/" + tripId + "/expenses").param("limit", "2").param("offset", "2")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }

    @Test
    @DisplayName("提醒板保持 {items,count,serverTime} 结构，items 分页、count 为总数")
    void reminderBoardKeepsShapeWithPagination() throws Exception {
        Account account = registerNew();
        LocalDateTime event = LocalDateTime.now().plusMinutes(10);
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        for (int i = 0; i < 2; i++) {
            LocalDateTime at = event.plusMinutes(i);
            mockMvc.perform(post("/api/schedule")
                            .header("Authorization", account.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "组会" + i,
                                    "date", at.format(dateFmt),
                                    "time", at.format(timeFmt)))))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/reminders").param("limit", "1").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.serverTime").exists())
                .andExpect(jsonPath("$.pagination.total").value(2))
                .andExpect(jsonPath("$.pagination.limit").value(1))
                .andExpect(jsonPath("$.pagination.hasMore").value(true));

        mockMvc.perform(get("/api/reminders").param("limit", "1").param("offset", "1")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.pagination.offset").value(1))
                .andExpect(jsonPath("$.pagination.hasMore").value(false));
    }
}
