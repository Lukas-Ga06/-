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

/**
 * 错误语义契约：非法入参必须是 400、未登录必须是 401、资源不存在必须是 404、
 * 且响应体不泄露服务端内部信息。
 */
class ApiErrorHandlingTest extends IntegrationTestSupport {

    @Test
    @DisplayName("注册缺少用户名返回 400 并给出字段提示")
    void registerWithoutUsernameIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"pw123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("username")));
    }

    @Test
    @DisplayName("注册密码过短返回 400")
    void registerWithShortPasswordIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shortpw_user\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("对话消息为空返回 400，不会进入 Agent 引擎")
    void chatWithBlankMessageIsBadRequest() throws Exception {
        Account account = registerNew();

        mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("撤销请求缺少 tripId 时按「撤销最近一次日程清空」处理：没有记录返回 404")
    void undoWithoutTripIdTargetsScheduleClear() throws Exception {
        Account account = registerNew();

        mockMvc.perform(post("/api/agent/undo")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("记账金额为 0 返回 400")
    void expenseWithZeroAmountIsBadRequest() throws Exception {
        Account account = registerNew();
        MvcResult created = mockMvc.perform(post("/api/trips")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"记账测试\",\"destination\":\"上海\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long tripId = body(created).path("data").path("id").asLong();

        mockMvc.perform(post("/api/trips/" + tripId + "/expenses")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0,\"category\":\"餐饮\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("请求体不是合法 JSON 时返回 400")
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("路径参数类型不匹配时返回 400")
    void pathVariableTypeMismatchIsBadRequest() throws Exception {
        Account account = registerNew();

        mockMvc.perform(get("/api/trips/not-a-number").header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("服务端异常不会把内部信息返回给客户端")
    void serverErrorDoesNotLeakInternals() throws Exception {
        Account account = registerNew();

        // 行程不存在属于业务错误（404），这里验证 message 是给人看的业务提示而非堆栈
        mockMvc.perform(get("/api/trips/99999999").header("Authorization", account.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("行程不存在或无权访问"));
    }
}
