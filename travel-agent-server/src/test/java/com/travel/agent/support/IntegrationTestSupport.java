package com.travel.agent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试基类：真实 Spring 上下文 + MockMvc（挂载安全过滤器链）+ 内存数据库。
 * 使用测试 profile（{@code application-test.yml}），不访问网络、不调用真实大模型。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final String PASSWORD = "pw123456";

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    protected MockMvc mockMvc;

    /** 测试账号：id / 用户名 / JWT */
    public record Account(long id, String username, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    @BeforeEach
    void initMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 每次生成唯一用户名，避免测试之间因共享内存数据库互相干扰 */
    protected String uniqueUsername() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected Account registerNew() throws Exception {
        return register(uniqueUsername());
    }

    protected Account register(String username) throws Exception {
        String body = "{\"username\":\"%s\",\"password\":\"%s\",\"nickname\":\"%s\"}"
                .formatted(username, PASSWORD, username);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = body(result).path("data");
        return new Account(data.path("id").asLong(), data.path("username").asText(), data.path("token").asText());
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
