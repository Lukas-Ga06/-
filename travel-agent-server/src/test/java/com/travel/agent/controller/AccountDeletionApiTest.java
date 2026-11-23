package com.travel.agent.controller;

import com.travel.agent.dto.TripDtos;
import com.travel.agent.security.AuthCookieService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/auth/me} 的 HTTP 契约（P0-D）：
 * 缺密码 400、密码错误 401 且不删数据、正确密码 200 并清 Cookie，
 * 注销后原 token 不能再访问任何需要账号的接口，也无法再登录。
 */
class AccountDeletionApiTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String passwordBody(String password) {
        return "{\"password\":\"" + password + "\"}";
    }

    private long createTrip(long userId, String name) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName(name);
        request.setDestination("成都");
        request.setBudget(BigDecimal.valueOf(2000));
        return tripService.create(userId, request).getId();
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("缺请求体、空对象或空白密码都返回 400，账号与数据保持不变")
    void missingPasswordIsRejected() throws Exception {
        Account account = registerNew();
        createTrip(account.id(), "注销前的行程");

        mockMvc.perform(delete("/api/auth/me").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入密码确认注销"));

        mockMvc.perform(delete("/api/auth/me")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(delete("/api/auth/me")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/auth/me").header("Authorization", account.bearer()))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", account.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("密码错误返回 401「密码错误」，不泄露账号是否存在，也不删除任何数据")
    void wrongPasswordIsRejectedWithoutDeleting() throws Exception {
        Account account = registerNew();
        createTrip(account.id(), "密码错误时保留的行程");

        mockMvc.perform(delete("/api/auth/me")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("密码错误"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(account.username()));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(account.username(), PASSWORD)))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", account.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM trip WHERE user_id = ?", account.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("正确密码注销：清 Cookie、删光本人数据、原 token 与登录都失效，其他人不受影响")
    void successfulDeletionClearsCookieAndRevokesAccess() throws Exception {
        Account victim = registerNew();
        Account bystander = registerNew();
        createTrip(victim.id(), "要删掉的行程");
        createTrip(bystander.id(), "别人的行程");

        MvcResult result = mockMvc.perform(delete("/api/auth/me")
                        .header("Authorization", victim.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(AuthCookieService.COOKIE_NAME + "=");
        assertThat(setCookie).contains("Max-Age=0");

        // 原 token 仍在有效期内，但账号已不存在：requireUser 语义 401，不能继续访问
        mockMvc.perform(get("/api/auth/me").header("Authorization", victim.bearer()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/trips").header("Authorization", victim.bearer()))
                .andExpect(status().isUnauthorized());
        // 账号也没了，无法重新登录
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(victim.username(), PASSWORD)))
                .andExpect(status().isUnauthorized());

        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", victim.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM trip WHERE user_id = ?", victim.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM trip WHERE user_id = ?", bystander.id())).isEqualTo(1);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bystander.bearer()))
                .andExpect(status().isOk());

        // 重复注销：用户已不存在 → 401，而不是 500
        mockMvc.perform(delete("/api/auth/me")
                        .header("Authorization", victim.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }
}