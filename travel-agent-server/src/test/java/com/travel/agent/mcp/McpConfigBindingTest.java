package com.travel.agent.mcp;

import com.travel.agent.config.McpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐 server 治理的配置绑定测试：证明运维**不重建镜像**、只改环境变量就能让
 * {@code allow-tools} / {@code max-calls-per-minute} / {@code failure-threshold} / {@code cooldown-ms}
 * 四个开关生效，且不写变量时行为与治理前完全一致；同时把「list 元素」两条覆盖写法的真实语义钉成测试
 * （文档里说的坑必须有可复现的读数，不能只凭印象）。
 *
 * <p>为什么不用 {@code @SpringBootTest(properties=...)}：那只是普通 {@code MapPropertySource}，
 * 与生产容器里的环境变量不是同一条绑定路径。这里把变量装进真实的
 * {@link SystemEnvironmentPropertySource}（Spring 读 {@code getenv()} 用的同一个类），
 * 再挂到真实的 {@code application.yml} 之上——宽松绑定、占位符解析、逗号分隔转列表、索引合并
 * 走的都是生产路径。本机真实环境变量会被摘掉，断言只依赖用例显式给出的变量。
 *
 * <p>行为断言（而非只比对字段值）用 {@link McpGovernor} 完成：配置绑定对了但没被消费，测试应该红。
 */
class McpConfigBindingTest {

    private static final String SYSTEM_ENVIRONMENT = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;

    /** 最小 Spring Boot 应用：只加载真实 application.yml + McpProperties，不碰数据库/Web */
    @SpringBootConfiguration
    @EnableConfigurationProperties(McpProperties.class)
    static class TestApp { }

    /** 用给定「环境变量」启动一次真实上下文并取出绑定结果；上下文随即关闭 */
    private static McpProperties bind(Map<String, String> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(SYSTEM_ENVIRONMENT);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                SYSTEM_ENVIRONMENT, new LinkedHashMap<>(environmentVariables)));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run()) {
            return context.getBean(McpProperties.class);
        }
    }

    private static McpProperties.Server onlyAmapServer(McpProperties properties) {
        assertEquals(1, properties.getServers().size(), "application.yml 应有且只有一个 amap server");
        return properties.getServers().get(0);
    }

    @Test
    @DisplayName("只改扁平环境变量：四个治理开关生效，且 command/env/read-only/timeout 不被波及")
    void flatEnvironmentOverridesFourGovernanceKnobs() {
        McpProperties properties = bind(Map.of(
                "MCP_AMAP_ALLOW_TOOLS", "maps_geo,maps_text_search,maps_weather",
                "MCP_AMAP_MAX_CALLS_PER_MINUTE", "30",
                "MCP_AMAP_FAILURE_THRESHOLD", "5",
                "MCP_AMAP_COOLDOWN_MS", "120000",
                "AMAP_MAPS_API_KEY", "test-placeholder-key"));
        McpProperties.Server amap = onlyAmapServer(properties);

        assertEquals("amap", amap.getName());
        assertEquals(List.of("maps_geo", "maps_text_search", "maps_weather"), amap.getAllowTools(),
                "逗号分隔的环境变量应绑定成白名单列表（写成 JSON 数组会变成一个元素）");
        assertEquals(30, amap.getMaxCallsPerMinute());
        assertEquals(5, amap.getFailureThreshold());
        assertEquals(120_000L, amap.getCooldownMs());

        assertEquals(List.of("npx", "-y", "@amap/amap-maps-mcp-server"), amap.getCommand(),
                "治理变量只覆盖治理四项，启动命令不受影响");
        assertEquals("test-placeholder-key", amap.getEnv().get("AMAP_MAPS_API_KEY"),
                "子进程 Key 仍由 AMAP_MAPS_API_KEY 提供，与治理变量互不影响");
        assertTrue(amap.isReadOnly());
        assertEquals(30_000L, amap.getTimeoutMs());

        // 白名单与配额：绑定值必须真的进入闸门
        AtomicLong now = new AtomicLong(1_000_000L);
        McpGovernor governor = new McpGovernor("amap", amap, now::get);
        assertTrue(governor.permitsTool("maps_geo"));
        assertFalse(governor.permitsTool("maps_direction_driving"), "白名单外的工具应被拒绝");
        assertEquals(403, governor.beforeCall("maps_direction_driving").statusCode());

        for (int i = 1; i <= 30; i++) {
            assertTrue(governor.beforeCall("maps_geo").allowed(), "配额内第 " + i + " 次应放行");
        }
        McpGovernor.Decision overQuota = governor.beforeCall("maps_geo");
        assertFalse(overQuota.allowed(), "第 31 次应被每分钟配额拒绝");
        assertEquals(503, overQuota.statusCode());
        assertEquals(30, governor.maxCallsPerMinute());

        // 熔断阈值与冷却：换一个干净窗口的闸门，避免配额拒绝先于熔断拒绝
        McpGovernor circuit = new McpGovernor("amap", amap, now::get);
        for (int i = 0; i < 5; i++) {
            circuit.recordFailure("10021");
        }
        assertEquals(McpGovernor.CircuitState.OPEN, circuit.snapshot().circuit(), "连续失败 5 次即打开熔断");
        McpGovernor.Decision fastFail = circuit.beforeCall("maps_geo");
        assertFalse(fastFail.allowed());
        assertEquals(503, fastFail.statusCode());
        assertTrue(fastFail.reason().contains("熔断中"), fastFail.reason());

        now.addAndGet(120_000L);
        assertTrue(circuit.beforeCall("maps_geo").allowed(), "冷却 120s 结束后放行半开探测");
        assertEquals(McpGovernor.CircuitState.HALF_OPEN, circuit.snapshot().circuit());
    }

    @Test
    @DisplayName("不写环境变量：与治理前一致（空白名单、不限次、不熔断、冷却默认 60s）")
    void absentEnvironmentKeepsLegacyDefaults() {
        McpProperties.Server amap = onlyAmapServer(bind(Map.of()));

        assertTrue(amap.getAllowTools().isEmpty(),
                "未设置时必须绑定成空列表（不是 [\"\"]），否则白名单会变成限制到空工具");
        assertEquals(0, amap.getMaxCallsPerMinute());
        assertEquals(0, amap.getFailureThreshold());
        assertEquals(60_000L, amap.getCooldownMs());

        McpGovernor governor = new McpGovernor("amap", amap, () -> 1_000_000L);
        for (int i = 0; i < 50; i++) {
            assertTrue(governor.beforeCall("maps_any_tool").allowed(), "默认配置不应拒绝调用");
            governor.recordFailure("boom " + i);
        }
        assertEquals(McpGovernor.CircuitState.CLOSED, governor.snapshot().circuit(), "阈值为 0 时不熔断");
        assertEquals(0, governor.snapshot().callsThisMinute(), "未配置配额时不统计分钟窗口");
    }

    @Test
    @DisplayName("已知限制：SPRING_APPLICATION_JSON 覆盖 servers[0] 会整条替换该元素，必须写全")
    void springApplicationJsonReplacesWholeServerEntry() {
        String json = "{\"travel\":{\"mcp\":{\"servers\":[{\"allow-tools\":[\"maps_geo\",\"maps_weather\"],"
                + "\"max-calls-per-minute\":12}]}}}";
        McpProperties properties = bind(Map.of("SPRING_APPLICATION_JSON", json));
        McpProperties.Server amap = onlyAmapServer(properties);

        assertEquals(List.of("maps_geo", "maps_weather"), amap.getAllowTools());
        assertEquals(12, amap.getMaxCallsPerMinute());
        // 高优先级来源只要出现 servers[0]，该元素整条来自它：yml 的 name/command/env/timeout 全部丢失，
        // 结果是启动期以 unnamed 接入失败（WARN + mcpToolCount=0）。所以这条路线必须整条复制。
        assertNull(amap.getName(), "JSON 只写治理项时 name 会丢——这正是整条替换的证据");
        assertNull(amap.getCommand(), "command 同样会丢，server 起不来");
        assertTrue(amap.getEnv().isEmpty(), "子进程 Key 也会丢");
        assertEquals(20_000L, amap.getTimeoutMs(), "未写的字段回落到 McpProperties 的 Java 默认值，不是 yml 值");
    }

    @Test
    @DisplayName("index 绑定坑：TRAVEL_MCP_SERVERS_0_* 同样整条替换元素，且不推荐作为运维写法")
    void indexedEnvironmentReplacesWholeServerEntry() {
        McpProperties properties = bind(Map.of(
                "TRAVEL_MCP_SERVERS_0_MAX_CALLS_PER_MINUTE", "17"));
        McpProperties.Server amap = onlyAmapServer(properties);

        assertEquals(17, amap.getMaxCallsPerMinute(), "index 形式确实能绑到该字段");
        assertNull(amap.getName(), "但同时把 yml 里该元素的 name/command/env 全部挤掉");
        assertNull(amap.getCommand());
        assertTrue(amap.getEnv().isEmpty());
    }

    @Test
    @DisplayName("SPRING_APPLICATION_JSON 整条写全时可用（含 name/command/env/timeout 的完整写法）")
    void springApplicationJsonWithCompleteServerEntryWorks() {
        String json = "{\"travel\":{\"mcp\":{\"servers\":[{"
                + "\"name\":\"amap\","
                + "\"command\":[\"npx\",\"-y\",\"@amap/amap-maps-mcp-server\"],"
                + "\"env\":{\"AMAP_MAPS_API_KEY\":\"${AMAP_MAPS_API_KEY:}\"},"
                + "\"read-only\":true,"
                + "\"timeout-ms\":30000,"
                + "\"allow-tools\":[\"maps_geo\",\"maps_weather\"],"
                + "\"max-calls-per-minute\":12,"
                + "\"failure-threshold\":4,"
                + "\"cooldown-ms\":90000}]}}}";
        McpProperties properties = bind(Map.of(
                "SPRING_APPLICATION_JSON", json,
                "AMAP_MAPS_API_KEY", "test-placeholder-key"));
        McpProperties.Server amap = onlyAmapServer(properties);

        assertEquals("amap", amap.getName());
        assertEquals(List.of("npx", "-y", "@amap/amap-maps-mcp-server"), amap.getCommand());
        assertEquals("test-placeholder-key", amap.getEnv().get("AMAP_MAPS_API_KEY"),
                "JSON 里的 ${AMAP_MAPS_API_KEY:} 仍按环境变量解析");
        assertEquals(30_000L, amap.getTimeoutMs());
        assertEquals(List.of("maps_geo", "maps_weather"), amap.getAllowTools());
        assertEquals(12, amap.getMaxCallsPerMinute());
        assertEquals(4, amap.getFailureThreshold());
        assertEquals(90_000L, amap.getCooldownMs());

        McpGovernor governor = new McpGovernor("amap", amap, () -> 1_000_000L);
        assertFalse(governor.permitsTool("maps_text_search"), "白名单外的工具应被拒绝");
        assertEquals(403, governor.beforeCall("maps_text_search").statusCode());
    }
}