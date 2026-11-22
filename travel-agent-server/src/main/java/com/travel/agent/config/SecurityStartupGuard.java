package com.travel.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * 启动期配置体检：把"默认配置安全"变成可见的告警。
 *
 * <p>背景：H2 控制台（sa 空密码）、demo/demo123 演示账号、cookie-secure=false 这三项在本地开发很顺手，
 * 但一旦随默认配置上生产就是"匿名读写全库 / 公开弱口令 / 登录态明文传输"。
 *
 * <p>策略：**只 WARN，绝不抛异常**。抛异常会让 docker compose（mysql profile）与集成测试直接起不来，
 * 反而促使运维关掉这项检查；醒目日志加 README 说明才是可持续的。
 * 默认（H2/test）profile 只输出 debug，避免正常开发被噪音淹没。
 */
@Component
public class SecurityStartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupGuard.class);

    /** 触发"生产告警"的 profile 关键字：mysql 与 prod 都属于对外部署形态 */
    private static final String[] PRODUCTION_PROFILE_HINTS = {"mysql", "prod"};

    private final Environment environment;

    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Value("${travel.demo-account.enabled:true}")
    private boolean demoAccountEnabled;

    @Value("${travel.jwt.cookie-secure:false}")
    private boolean cookieSecure;

    public SecurityStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    /** 可单独调用，便于测试 */
    public void check() {
        String[] activeProfiles = activeProfiles();
        boolean production = isProduction(activeProfiles);
        if (!production) {
            log.debug("配置体检：非生产 profile（active={}），H2 控制台={}，demo 账号={}，cookie-secure={}",
                    Arrays.toString(activeProfiles), h2ConsoleEnabled, demoAccountEnabled, cookieSecure);
            return;
        }
        String profileText = Arrays.toString(activeProfiles);
        if (h2ConsoleEnabled) {
            log.warn("[SECURITY] 生产 profile {} 下 H2 控制台应关闭（H2_CONSOLE_ENABLED=false）："
                    + "控制台以 sa 空密码直连数据库，暴露即等于匿名读写全库", profileText);
        }
        if (demoAccountEnabled) {
            log.warn("[SECURITY] 生产 profile {} 下 demo 账号应关闭（DEMO_ACCOUNT=false）："
                    + "demo/demo123 是公开弱口令，公网可被直接登录", profileText);
        }
        if (!cookieSecure) {
            log.warn("[SECURITY] 生产 profile {} 下 cookie-secure=false：登录 Cookie 无 Secure 属性，"
                    + "http 下可被嗅探，https 部署应设 COOKIE_SECURE=true", profileText);
        }
    }

    private String[] activeProfiles() {
        String[] active = environment.getActiveProfiles();
        if (active.length > 0) {
            return active;
        }
        // 某些启动方式（直接 -Dspring.profiles.active）只设置属性而非 profile 数组
        String property = environment.getProperty("spring.profiles.active", "");
        return property.isBlank() ? new String[0] : property.split(",");
    }

    private static boolean isProduction(String[] profiles) {
        for (String profile : profiles) {
            if (profile == null) {
                continue;
            }
            String normalized = profile.trim().toLowerCase(Locale.ROOT);
            for (String hint : PRODUCTION_PROFILE_HINTS) {
                if (normalized.contains(hint)) {
                    return true;
                }
            }
        }
        return false;
    }
}