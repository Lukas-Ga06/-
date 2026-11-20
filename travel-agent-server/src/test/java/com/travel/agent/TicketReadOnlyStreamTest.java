package com.travel.agent;

import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.security.AuthCookieService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 票据只读会话：仅凭 {@code ?ticket=} 认证（没有 Cookie / Authorization）的 SSE 连接
 * 不得执行记账/备忘/日程这类直接落库的写意图。
 *
 * <p>回归背景：票据只允许两个 GET 路径本是好事，但 {@code /api/agent/stream} 能执行
 * RECORD_EXPENSE / ADD_MEMO / ADD_SCHEDULE / GEN_DAILY_REPORT——泄漏一条 URL
 * 就等于 5 分钟内以受害者身份写数据。
 *
 * <p>对照组很关键：同一请求带上 httpOnly Cookie 后写入必须照常发生，
 * 证明拦截发生在"凭证强度"维度，而不是把功能本身弄坏了。
 */
class TicketReadOnlyStreamTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String issueTicket(Account account) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/access-ticket").param("purpose", "agent-stream")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("ticket").asText();
    }

    private Trip newTrip(Account account) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("票据只读测试行程");
        request.setDestination("杭州");
        request.setBudget(BigDecimal.valueOf(2000));
        return tripService.create(account.id(), request);
    }

    /** GET /api/agent/stream 跑到完成，返回响应体（SSE 文本） */
    private String streamAndComplete(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return sseDispatch(builder).getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long expenseCount(long tripId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from expense where trip_id = ?", Long.class, tripId);
        return count == null ? 0 : count;
    }

    private long memoCount(long userId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from memo where user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("ticket-only 的 /api/agent/stream?message=记一下 20 元 不产生新账单；带 Cookie 时照常记账")
    void ticketOnlyStreamCannotRecordExpense() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account);
        String ticket = issueTicket(account);
        String message = "记一下 20 元";

        String ticketOnlyBody = streamAndComplete(get("/api/agent/stream")
                .param("message", message)
                .param("tripId", String.valueOf(trip.getId()))
                .param("ticket", ticket));
        assertThat(ticketOnlyBody).contains("只读票据会话");
        assertThat(expenseCount(trip.getId())).as("票据会话不得落账").isZero();

        // 对照：同一请求带 httpOnly Cookie（浏览器主路径）必须照常写入
        String cookieBody = streamAndComplete(get("/api/agent/stream")
                .cookie(new Cookie(AuthCookieService.COOKIE_NAME, account.token()))
                .param("message", message)
                .param("tripId", String.valueOf(trip.getId())));
        assertThat(cookieBody).doesNotContain("只读票据会话");
        assertThat(expenseCount(trip.getId())).as("Cookie 会话照常记账").isEqualTo(1L);
    }

    @Test
    @DisplayName("ticket-only 的 SSE 也不能添加备忘；带 Cookie 时照常添加")
    void ticketOnlyStreamCannotAddMemo() throws Exception {
        Account account = registerNew();
        String ticket = issueTicket(account);

        String ticketOnlyBody = streamAndComplete(get("/api/agent/stream")
                .param("message", "记一下明天办签证")
                .param("ticket", ticket));
        assertThat(ticketOnlyBody).contains("只读票据会话");
        assertThat(memoCount(account.id())).as("票据会话不得写备忘").isZero();

        streamAndComplete(get("/api/agent/stream")
                .cookie(new Cookie(AuthCookieService.COOKIE_NAME, account.token()))
                .param("message", "记一下明天办签证"));
        assertThat(memoCount(account.id())).as("Cookie 会话照常写备忘").isEqualTo(1L);
    }

    @Test
    @DisplayName("规划类意图不受票据只读限制：ticket-only 仍能走完规划（提交确认仍需 POST，票据无法确认）")
    void ticketOnlyStreamStillAllowsPlanning() throws Exception {
        Account account = registerNew();
        String ticket = issueTicket(account);

        String bodyText = streamAndComplete(get("/api/agent/stream")
                .param("message", "帮我规划上海三日游，2人，预算3000元")
                .param("ticket", ticket));

        assertThat(bodyText).contains("complete");
        assertThat(bodyText).doesNotContain("只读票据会话");
    }
}