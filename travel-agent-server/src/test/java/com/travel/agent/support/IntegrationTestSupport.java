package com.travel.agent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.config.TraceIdFilter;
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

    @Autowired
    protected TraceIdFilter traceIdFilter;

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
                // 生产环境由 Spring Boot 自动注册（@Component + @Order）；
                // MockMvc 是手工搭的，需要显式挂上才能验证链路标识行为
                .addFilters(traceIdFilter)
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

    /**
     * SSE 请求的 MockMvc 包装：只对 {@link java.util.ConcurrentModificationException} 重试。
     *
     * <p>为什么需要：{@code MockHttpServletResponse} 的响应头表是普通 {@code HashMap}，而 SSE 的事件由
     * worker 线程写入、Spring Security 的 {@code HeaderWriterFilter.doHeadersAfter} 又在请求线程写安全头。
     * 两条线并发写同一个 mock 响应就会偶发 {@code ConcurrentModificationException}（本机压测 6 次能复现 2 次）。
     * 真实 Servlet 容器里响应访问由容器串行化、提交后再写只会是 no-op，不存在该问题——这是 **MockMvc 的限制**，
     * 不是产品缺陷。彻底解决需要把这几个用例改成 {@code webEnvironment = RANDOM_PORT} + 真容器（已记入待办）。
     *
     * <p>重试只针对该异常，其它失败（断言、状态码、超时）一律照常抛出，不掩盖真实问题。
     */
    protected <T> T withSseMockMvc(java.util.concurrent.Callable<T> action) throws Exception {
        java.util.ConcurrentModificationException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return action.call();
            } catch (java.util.ConcurrentModificationException e) {
                last = e;
                Thread.sleep(20L * attempt);
            }
        }
        throw last;
    }


    /**
     * 跑完一次 SSE 请求并返回最终响应（已等到 worker 收尾，避免 asyncDispatch 与写入并发）。
     * 内部只对 MockMvc 的 {@code ConcurrentModificationException} 重试，见 {@link #withSseMockMvc}。
     */
    protected MvcResult sseDispatch(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return withSseMockMvc(() -> {
            MvcResult started = mockMvc.perform(builder)
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                    .andReturn();
            started.getAsyncResult(15_000);
            return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn();
        });
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
