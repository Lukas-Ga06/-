package com.travel.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 重复日程的下一次发生日期计算 */
class RecurrenceTest {

    @Test
    @DisplayName("每周：取 from 当天或之后的下一次")
    void weeklyNextOccurrence() {
        LocalDate start = LocalDate.of(2026, 10, 6);

        assertThat(Recurrence.nextOnOrAfter(start, Recurrence.WEEKLY, null, LocalDate.of(2026, 10, 20)))
                .isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(Recurrence.nextOnOrAfter(start, Recurrence.WEEKLY, null, LocalDate.of(2026, 10, 21)))
                .isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    @DisplayName("每天 / 每月")
    void dailyAndMonthly() {
        assertThat(Recurrence.nextOnOrAfter(LocalDate.of(2026, 10, 1), Recurrence.DAILY, null,
                LocalDate.of(2026, 10, 20))).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(Recurrence.nextOnOrAfter(LocalDate.of(2026, 1, 31), Recurrence.MONTHLY, null,
                LocalDate.of(2026, 2, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    @DisplayName("不重复：过去即结束；重复：超过 repeatUntil 即结束")
    void endedSeriesReturnsNull() {
        assertThat(Recurrence.nextOnOrAfter(LocalDate.of(2026, 10, 6), Recurrence.NONE, null,
                LocalDate.of(2026, 10, 7))).isNull();
        assertThat(Recurrence.nextOnOrAfter(LocalDate.of(2026, 10, 6), Recurrence.WEEKLY,
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 20))).isNull();
    }

    @Test
    @DisplayName("规则归一化与中文标签")
    void normalizeAndLabel() {
        assertThat(Recurrence.normalize("WEEKLY")).isEqualTo(Recurrence.WEEKLY);
        assertThat(Recurrence.normalize("BOGUS")).isEqualTo(Recurrence.NONE);
        assertThat(Recurrence.normalize(null)).isEqualTo(Recurrence.NONE);
        assertThat(Recurrence.label(Recurrence.WEEKLY)).isEqualTo("每周");
        assertThat(Recurrence.isRecurring(Recurrence.MONTHLY)).isTrue();
        assertThat(Recurrence.isRecurring(Recurrence.NONE)).isFalse();
    }

    @Test
    @DisplayName("带时刻：今天该时刻已过则顺延到下一次")
    void nextOccurrenceSkipsPassedTime() {
        LocalDate start = LocalDate.of(2026, 10, 6);
        LocalTime nine = LocalTime.of(9, 0);

        assertThat(Recurrence.nextOccurrence(start, Recurrence.DAILY, null, nine,
                LocalDateTime.of(2026, 10, 17, 18, 50))).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(Recurrence.nextOccurrence(start, Recurrence.DAILY, null, nine,
                LocalDateTime.of(2026, 10, 17, 8, 0))).isEqualTo(LocalDate.of(2026, 10, 17));
    }
}
