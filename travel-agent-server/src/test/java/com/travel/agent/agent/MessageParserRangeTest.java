package com.travel.agent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 明确说出的日期范围解析（不启 Spring）。
 * 回归背景：「下下周一」里含有子串「下周一」，旧实现先命中「下周」把它解析成下周，
 * 清空日程的删除范围与空闲查询的查询范围都会早一周。
 */
class MessageParserRangeTest {

    /** 2026-11-27 是周五：下周 = 11/30 起，下下周 = 12/7 起 */
    private static final LocalDate BASE = LocalDate.of(2026, 11, 27);

    private static MessageParser.DateRange range(String text) {
        return MessageParser.parseExplicitRange(text, BASE);
    }

    private static void assertDay(String text, LocalDate expected) {
        assertThat(range(text)).as(text).isNotNull();
        assertThat(range(text).from()).as(text).isEqualTo(expected);
        assertThat(range(text).to()).as(text).isEqualTo(expected);
    }

    private static void assertWeek(String text, LocalDate monday) {
        assertThat(range(text)).as(text).isNotNull();
        assertThat(range(text).from()).as(text).isEqualTo(monday);
        assertThat(range(text).to()).as(text).isEqualTo(monday.plusDays(6));
    }

    @Test
    @DisplayName("明天 / 下周一 按单日解析")
    void singleDays() {
        assertDay("明天", BASE.plusDays(1));
        assertDay("下周一", LocalDate.of(2026, 11, 30));
    }

    @Test
    @DisplayName("下周 = 下周一到周日")
    void nextWeek() {
        assertWeek("下周", LocalDate.of(2026, 11, 30));
        assertWeek("这个下周的安排都清掉", LocalDate.of(2026, 11, 30));
    }

    @Test
    @DisplayName("下下周 = 下下周一到周日，不被「下周」提前一周命中")
    void weekAfterNext() {
        assertWeek("下下周", LocalDate.of(2026, 12, 7));
        assertWeek("下下周清空日程", LocalDate.of(2026, 12, 7));
    }

    @Test
    @DisplayName("下下周X = 下下周的星期几")
    void weekAfterNextWeekday() {
        assertDay("下下周一", LocalDate.of(2026, 12, 7));
        assertDay("下下周日", LocalDate.of(2026, 12, 13));
        assertDay("下下周三下午有空吗", LocalDate.of(2026, 12, 9));
    }

    @Test
    @DisplayName("空闲查询的显式路径同样认下下周；没说日期才默认明天")
    void freeTimeRangeUsesWeekAfterNext() {
        MessageParser.DateRange explicit = MessageParser.parseFreeTimeRange("下下周", BASE);
        assertThat(explicit.from()).isEqualTo(LocalDate.of(2026, 12, 7));
        assertThat(explicit.to()).isEqualTo(LocalDate.of(2026, 12, 13));

        MessageParser.DateRange single = MessageParser.parseFreeTimeRange("下下周一空闲吗", BASE);
        assertThat(single.from()).isEqualTo(LocalDate.of(2026, 12, 7));
        assertThat(single.to()).isEqualTo(LocalDate.of(2026, 12, 7));

        MessageParser.DateRange fallback = MessageParser.parseFreeTimeRange("明天有空吗", BASE);
        assertThat(fallback.from()).isEqualTo(BASE.plusDays(1));
        assertThat(fallback.to()).isEqualTo(BASE.plusDays(1));
    }

    @Test
    @DisplayName("一条日期词都没有 → null（由调用方决定默认值/先追问）")
    void withoutDateReturnsNull() {
        assertThat(range("清空所有日程")).isNull();
        assertThat(range("")).isNull();
        assertThat(range(null)).isNull();
    }
}