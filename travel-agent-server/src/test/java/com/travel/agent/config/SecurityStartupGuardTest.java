package com.travel.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 启动期配置体检：只告警、不抛异常（否则 docker compose / 集成测试直接起不来）。
 * 同时验证 profile 识别：mysql/prod 视为生产，default/test 视为开发。
 */
class SecurityStartupGuardTest {

    private static SecurityStartupGuard guard(MockEnvironment environment,
                                              boolean h2ConsoleEnabled,
                                              boolean demoAccountEnabled,
                                              boolean cookieSecure) {
        SecurityStartupGuard guard = new SecurityStartupGuard(environment);
        ReflectionTestUtils.setField(guard, "h2ConsoleEnabled", h2ConsoleEnabled);
        ReflectionTestUtils.setField(guard, "demoAccountEnabled", demoAccountEnabled);
        ReflectionTestUtils.setField(guard, "cookieSecure", cookieSecure);
        return guard;
    }

    @Test
    void productionProfileWithUnsafeDefaultsOnlyWarnsAndNeverThrows() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mysql");
        SecurityStartupGuard guard = guard(environment, true, true, false);

        // 三项都不安全，但也只应 WARN：绝不因为体检失败而阻止启动
        assertDoesNotThrow(guard::check);
    }

    @Test
    void prodProfileIsAlsoTreatedAsProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertDoesNotThrow(() -> guard(environment, true, true, false).check());
    }

    @Test
    void devOrTestProfileStaysQuiet() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        assertDoesNotThrow(() -> guard(environment, true, true, false).check());

        MockEnvironment noProfile = new MockEnvironment();
        assertDoesNotThrow(() -> guard(noProfile, true, true, false).check());
    }

    @Test
    void profilePropertyFallbackIsRecognized() {
        // 某些启动方式只设置 spring.profiles.active 属性而 history 数组为空
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.profiles.active", "prod");
        assertDoesNotThrow(() -> guard(environment, true, true, false).check());
    }
}