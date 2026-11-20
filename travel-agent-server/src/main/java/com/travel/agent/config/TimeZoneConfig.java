package com.travel.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * 统一应用时区。
 *
 * 代码里大量使用 {@code LocalDate.now()}（今天/明天/昨天、行程日期、记账日期、日程日期），
 * 它取的是 JVM 默认时区；而容器/服务器通常是 UTC，会让"今天"整体偏移 8 小时。
 * 这里在启动时把默认时区固定为配置值（默认 Asia/Shanghai），与 Jackson 的 time-zone 保持一致。
 */
@Configuration
@Slf4j
public class TimeZoneConfig {

    private final String zoneId;

    public TimeZoneConfig(@Value("${travel.timezone:Asia/Shanghai}") String zoneId) {
        this.zoneId = zoneId;
    }

    @PostConstruct
    void applyDefaultTimeZone() {
        ZoneId zone = ZoneId.of(zoneId);
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        log.info("应用时区已固定为 {}（可用环境变量 APP_TIMEZONE 覆盖）", zone);
    }
}
