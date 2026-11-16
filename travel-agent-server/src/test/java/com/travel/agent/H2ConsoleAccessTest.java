package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认配置加固：H2 控制台默认关闭时，/h2-console/** 不能匿名访问。
 *
 * <p>修复前 {@code SecurityConfig} 无条件 permitAll("/h2-console/**")，
 * 配合 application.yml 默认 {@code enabled: true} 与 sa 空密码，等于匿名可执行任意 SQL。
 */
@SpringBootTest(properties = "spring.h2.console.enabled=false")
class H2ConsoleAccessTest extends IntegrationTestSupport {

    @Test
    @DisplayName("H2 控制台关闭时匿名访问 /h2-console/ 返回 401（不再 permitAll）")
    void h2ConsoleIsNotPublicWhenDisabled() throws Exception {
        mockMvc.perform(get("/h2-console/"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/h2-console/login.jsp"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("H2 控制台关闭不影响其它免登录路径（健康检查）")
    void healthEndpointStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}