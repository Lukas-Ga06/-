package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用时区固定为 Asia/Shanghai：否则在默认 UTC 的容器里，"今天/明天"会整体偏移一天。
 */
class TimeZoneConfigTest extends IntegrationTestSupport {

    @Test
    @DisplayName("JVM 默认时区被固定为配置值，LocalDate.now() 与之一致")
    void defaultTimeZoneIsFixed() {
        ZoneId expected = ZoneId.of("Asia/Shanghai");

        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(expected);
        assertThat(LocalDate.now()).isEqualTo(LocalDate.now(expected));
    }
}
