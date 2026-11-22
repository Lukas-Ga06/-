package com.travel.agent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 出发日期解析：具体日期 / 节日 / 相对日 / 周末 */
class MessageParserDepartureTest {

    @Test
    @DisplayName("完整日期优先解析")
    void parsesFullDate() {
        MessageParser.DepartureDate d = MessageParser.extractDepartureDate(
                "帮我规划 2026-12-05 杭州三日游", LocalDate.of(2026, 9, 20));

        assertThat(d.explicit()).isTrue();
        assertThat(d.date()).isEqualTo(LocalDate.of(2026, 12, 5));
    }

    @Test
    @DisplayName("月日：今年已过则顺延到明年")
    void monthDayRollsToNextYearWhenPast() {
        assertThat(MessageParser.extractDepartureDate("10月1日去杭州", LocalDate.of(2026, 9, 20)).date())
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(MessageParser.extractDepartureDate("10月1日去杭州", LocalDate.of(2026, 11, 1)).date())
                .isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(MessageParser.extractDepartureDate("10/1去杭州", LocalDate.of(2026, 9, 20)).date())
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("固定节日：国庆 / 元旦 / 五一")
    void parsesFixedFestivals() {
        assertThat(MessageParser.extractDepartureDate("国庆去杭州", LocalDate.of(2026, 9, 20)).date())
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(MessageParser.extractDepartureDate("国庆去杭州", LocalDate.of(2026, 10, 17)).date())
                .isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(MessageParser.extractDepartureDate("元旦去杭州", LocalDate.of(2026, 12, 20)).date())
                .isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(MessageParser.extractDepartureDate("五一去杭州", LocalDate.of(2026, 3, 1)).date())
                .isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    @DisplayName("相对日期")
    void parsesRelativeDays() {
        LocalDate today = LocalDate.of(2026, 9, 16);

        assertThat(MessageParser.extractDepartureDate("明天去杭州", today).date()).isEqualTo(today.plusDays(1));
        assertThat(MessageParser.extractDepartureDate("后天去杭州", today).date()).isEqualTo(today.plusDays(2));
        assertThat(MessageParser.extractDepartureDate("今天去杭州", today).date()).isEqualTo(today);
    }

    @Test
    @DisplayName("周末 / 本周六 / 下周六 / 下周末")
    void parsesWeekendAndWeekdays() {
        LocalDate wednesday = LocalDate.of(2026, 9, 16); // 周三
        LocalDate thisSaturday = LocalDate.of(2026, 9, 19);
        LocalDate nextSaturday = LocalDate.of(2026, 9, 26);

        assertThat(MessageParser.extractDepartureDate("周末去杭州", wednesday).date()).isEqualTo(thisSaturday);
        assertThat(MessageParser.extractDepartureDate("本周六去杭州", wednesday).date()).isEqualTo(thisSaturday);
        assertThat(MessageParser.extractDepartureDate("周六去杭州", wednesday).date()).isEqualTo(thisSaturday);
        assertThat(MessageParser.extractDepartureDate("下周六去杭州", wednesday).date()).isEqualTo(nextSaturday);
        assertThat(MessageParser.extractDepartureDate("下周末去杭州", wednesday).date()).isEqualTo(nextSaturday);
    }

    @Test
    @DisplayName("没提日期时 explicit=false，数字不会被误判成日期")
    void returnsNoneWhenNoDate() {
        MessageParser.DepartureDate none = MessageParser.extractDepartureDate(
                "预算3000元，玩三天，酒店近地铁", LocalDate.of(2026, 9, 16));

        assertThat(none.explicit()).isFalse();
        assertThat(none.date()).isNull();
    }

    @Test
    @DisplayName("日期里的「1日」不会被当成天数")
    void dateDoesNotBreakDayExtraction() {
        assertThat(MessageParser.extractDays("帮我规划11月1日杭州三日游")).isEqualTo(3);
        assertThat(MessageParser.extractDays("10/1去杭州玩两天")).isEqualTo(2);
        assertThat(MessageParser.extractDays("2026-12-05 杭州三日游")).isEqualTo(3);
        assertThat(MessageParser.extractDays("上海一日游")).isEqualTo(1);
        assertThat(MessageParser.extractDays("周末去杭州")).isEqualTo(2);
    }
}
