package com.travel.agent;

import com.travel.agent.security.AuthCookieService;
import com.travel.agent.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * httpOnly Cookie 登录态：登录/注册种 Cookie，浏览器凭 Cookie 认证，
 * Authorization 头仍然可用（非浏览器客户端兼容）。
 */
class CookieAuthTest extends IntegrationTestSupport {

    @Test
    @DisplayName("登录响应种下 httpOnly Cookie，后续请求凭 Cookie 认证通过")
    void loginSetsCookieAndCookieAuthenticates() throws Exception {
        String username = uniqueUsername();
        registerUser(username);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = login.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("travel_agent_token=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        // MockMvc 的 header("Cookie", ...) 不会解析成 Cookie 对象，用 .cookie(...) 才能进 getCookies()
        String tokenValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        Cookie authCookie = new Cookie(AuthCookieService.COOKIE_NAME, tokenValue);

        // 无 Authorization 头，仅凭 Cookie 访问需登录的接口
        mockMvc.perform(get("/api/trips").cookie(authCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    @DisplayName("无任何凭证访问 /api/auth/me 返回 401")
    void meWithoutCredentialsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登出清除 Cookie；Authorization 头路径不受影响")
    void logoutClearsCookieAndHeaderStillWorks() throws Exception {
        Account user = registerNew();

        MvcResult logout = mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = logout.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("Max-Age=0");

        // Authorization 头兼容路径继续可用（e2e 脚本与 curl）
        mockMvc.perform(get("/api/trips").header("Authorization", user.bearer()))
                .andExpect(status().isOk());
    }

    private void registerUser(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\",\"nickname\":\"%s\"}"
                                .formatted(username, PASSWORD, username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }
}
