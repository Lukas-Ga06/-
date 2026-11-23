package com.travel.agent.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * P0-A：启动时异步探测 {@code GET {baseUrl}/models}。
 *
 * <p>全部用 MockRestServiceServer，不访问真实网络：模型在清单中 → 可用；
 * 不在清单中 → 不可用 + 列出现有模型；请求失败 → 未校验（null）+ 原因，
 * 只打 WARN、不打 ERROR 堆栈，也不阻塞调用方。
 */
class LlmModelProbeTest {

    private static final String MODELS_JSON = """
            {"object":"list","data":[
              {"id":"deepseek-v4-pro","object":"model"},
              {"id":"deepseek-flash","object":"model"}]}
            """;

    private LlmProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private LlmModelProbe probe;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test-secret");
        properties.setBaseUrl("https://api.example.com/v1/");
        properties.setModel("deepseek-flash");
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        probe = new LlmModelProbe(properties);
    }

    @AfterEach
    void tearDown() {
        server.verify();
    }

    @Test
    @DisplayName("模型在清单中 → available=true、无错误、不处于 pending")
    void configuredModelPresent() {
        server.expect(requestTo(containsString("https://api.example.com/v1/models")))
                .andRespond(withSuccess(MODELS_JSON, MediaType.APPLICATION_JSON));

        probe.probeWith(builder.build());

        LlmModelProbe.ModelCheck check = probe.status();
        assertThat(check.available()).isTrue();
        assertThat(check.error()).isNull();
        assertThat(check.pending()).isFalse();
    }

    @Test
    @DisplayName("模型不在清单中 → available=false，原因里列出现有模型（换模型要用 /models 核对）")
    void configuredModelMissing() {
        properties.setModel("deepseek-chat");
        server.expect(requestTo(containsString("/v1/models")))
                .andRespond(withSuccess(MODELS_JSON, MediaType.APPLICATION_JSON));

        probe.probeWith(builder.build());

        LlmModelProbe.ModelCheck check = probe.status();
        assertThat(check.available()).isFalse();
        assertThat(check.pending()).isFalse();
        assertThat(check.error())
                .contains("deepseek-chat")
                .contains("不在模型清单中")
                .contains("deepseek-flash")
                .contains("deepseek-v4-pro");
    }

    @Test
    @DisplayName("探测请求失败 → available=null（未校验）+ 可读原因，只 WARN 不打 ERROR")
    void probeFailureIsUnverifiedNotUnavailable() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmModelProbe.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            server.expect(requestTo(containsString("/v1/models"))).andRespond(withServerError());

            probe.probeWith(builder.build());

            LlmModelProbe.ModelCheck check = probe.status();
            assertThat(check.available()).as("请求失败不等于模型不存在").isNull();
            assertThat(check.pending()).isFalse();
            assertThat(check.error()).contains("无法读取模型清单");
            assertThat(check.error()).doesNotContain("sk-test-secret");
            assertThat(logs.list.stream().noneMatch(event -> event.getLevel() == Level.ERROR)).isTrue();
            assertThat(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("探测失败"))).isTrue();
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("模型名为空（部署环境设了空的 LLM_MODEL）→ 直接判不可用，不发请求")
    void blankModelIsReportedImmediately() {
        properties.setModel(" ");

        probe.probeWith(builder.build());

        LlmModelProbe.ModelCheck check = probe.status();
        assertThat(check.available()).isFalse();
        assertThat(check.error()).contains("未配置模型");
        assertThat(check.pending()).isFalse();
    }

    @Test
    @DisplayName("关闭 travel.llm.model-probe-enabled 时不发起探测，状态保持未校验")
    void disabledProbeDoesNotRun() {
        properties.setModelProbeEnabled(false);

        probe.maybeProbe();

        LlmModelProbe.ModelCheck check = probe.status();
        assertThat(check.available()).isNull();
        assertThat(check.error()).isNull();
        assertThat(check.pending()).isFalse();
    }

    @Test
    @DisplayName("providerHost 只返回 host（不含协议/路径/Key），无法解析时返回 null")
    void providerHostStripsPathAndScheme() {
        assertThat(properties.providerHost()).isEqualTo("api.example.com");

        properties.setBaseUrl("not a url");
        assertThat(properties.providerHost()).isNull();

        properties.setBaseUrl("");
        assertThat(properties.providerHost()).isNull();
    }
}
