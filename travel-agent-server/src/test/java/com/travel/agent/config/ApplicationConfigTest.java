package com.travel.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主配置结构回归。
 *
 * 起因：往 application.yml 里加一个顶层段（management）时插错位置，
 * 后面的 spring.* 全被 YAML 归到了新段下面 —— 应用静默回退到内存数据库，
 * 数据"看起来丢了"，而所有测试依然通过（测试用独立的 test profile）。
 * 这里断言主配置里几个关键路径仍然在正确的位置上。
 */
class ApplicationConfigTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMainConfig() {
        try (InputStream in = ApplicationConfigTest.class.getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml 必须在 classpath 上").isNotNull();
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
    }

    @Test
    @DisplayName("spring 段没有被别的顶层段截断")
    @SuppressWarnings("unchecked")
    void springSectionKeepsItsChildren() {
        Map<String, Object> root = loadMainConfig();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        assertThat(spring).isNotNull();
        assertThat(spring).containsKeys("datasource", "jpa", "servlet", "jackson");

        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
        assertThat(String.valueOf(datasource.get("url")))
                .as("默认配置必须用文件库，否则重启就丢数据")
                .startsWith("jdbc:h2:file:");

        Map<String, Object> jpa = (Map<String, Object>) spring.get("jpa");
        assertThat(jpa.get("open-in-view")).isEqualTo(false);
    }

    @Test
    @DisplayName("顶层段都在顶层：travel / management / logging")
    void topLevelSectionsStayTopLevel() {
        Map<String, Object> root = loadMainConfig();
        assertThat(root).containsKeys("server", "spring", "management", "travel", "logging");
        assertThat(root.get("travel")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("探活与链路标识相关配置在位")
    @SuppressWarnings("unchecked")
    void observabilityConfigPresent() {
        Map<String, Object> root = loadMainConfig();
        Map<String, Object> management = (Map<String, Object>) root.get("management");
        Map<String, Object> endpoints = (Map<String, Object>) management.get("endpoints");
        Map<String, Object> web = (Map<String, Object>) endpoints.get("web");
        Map<String, Object> exposure = (Map<String, Object>) web.get("exposure");
        assertThat(String.valueOf(exposure.get("include"))).contains("health");

        Map<String, Object> logging = (Map<String, Object>) root.get("logging");
        Map<String, Object> pattern = (Map<String, Object>) logging.get("pattern");
        assertThat(String.valueOf(pattern.get("level"))).contains("traceId");
    }
}
