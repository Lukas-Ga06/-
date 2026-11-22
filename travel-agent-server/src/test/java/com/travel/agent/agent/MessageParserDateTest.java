package com.travel.agent.agent;

import com.travel.agent.entity.Trip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「今天/明天/后天」→ 行程第几天的映射。
 * 回归背景：旧实现用绝对日期算 dayIndex 再 clamp，行程日期与今天不重合时会「说明天却改了最后一天」。
 */
class MessageParserDateTest {

    private static Trip tripWith(LocalDate start, int days) {
        return Trip.builder()
                .startDate(start)
                .endDate(start.plusDays(days - 1L))
                .build();
    }

    @Test
    @DisplayName("相对日期识别：今天/明天/后天")
    void detectsRelativeDate() {
        assertThat(MessageParser.relativeDayOffset("后天出发")).isEqualTo(2);
        assertThat(MessageParser.relativeDayOffset("明天换掉户外景点")).isEqualTo(1);
        assertThat(MessageParser.relativeDayOffset("今晚的安排改一下")).isZero();
        assertThat(MessageParser.relativeDayOffset("把户外景点换掉")).isEqualTo(MessageParser.NO_RELATIVE_DATE);
        assertThat(MessageParser.relativeDayOffset(null)).isEqualTo(MessageParser.NO_RELATIVE_DATE);
    }

    @Test
    @DisplayName("提到的日期落在行程内 → 直接用那天，不算行程外")
    void dateInsideTripMapsDirectly() {
        LocalDate today = LocalDate.now();
        Trip trip = tripWith(today.plusDays(1), 3);

        MessageParser.TripDay day = MessageParser.resolveTripDay(trip, 1, "明天下雨，把户外景点换掉");

        assertThat(day.dayIndex()).isEqualTo(1);
        assertThat(day.outsideTrip()).isFalse();
        assertThat(day.date()).isEqualTo(today.plusDays(1));
    }

    @Test
    @DisplayName("行程已结束：说明天不再被 clamp 成最后一天，而是从当前天顺推")
    void pastTripUsesRelativeOffsetInsteadOfClamping() {
        LocalDate today = LocalDate.now();
        Trip trip = tripWith(today.minusDays(8), 3); // 9/14-9/16 这类已结束行程

        MessageParser.TripDay day = MessageParser.resolveTripDay(trip, 1, "明天下雨，把户外景点换掉");

        // 旧实现：绝对日期映射后 clamp → Day 3（错）；新实现：当前天 1 + 1 = Day 2
        assertThat(day.dayIndex()).isEqualTo(2);
        assertThat(day.outsideTrip()).isTrue();
    }

    @Test
    @DisplayName("行程还没开始：顺推后不超过行程总天数")
    void futureTripClampsRelativeOffsetWithinTrip() {
        LocalDate today = LocalDate.now();
        Trip trip = tripWith(today.plusDays(10), 3);

        MessageParser.TripDay day = MessageParser.resolveTripDay(trip, 3, "明天把户外景点换掉");

        assertThat(day.dayIndex()).isEqualTo(3);
        assertThat(day.outsideTrip()).isTrue();
    }

    @Test
    @DisplayName("没提日期就用当前聚焦的那天")
    void withoutDateUsesCurrentDay() {
        LocalDate today = LocalDate.now();
        Trip trip = tripWith(today.plusDays(1), 3);

        MessageParser.TripDay day = MessageParser.resolveTripDay(trip, 2, "把户外景点换掉");

        assertThat(day.dayIndex()).isEqualTo(2);
        assertThat(day.outsideTrip()).isFalse();
        assertThat(day.date()).isEqualTo(today.plusDays(2));
    }

    @Test
    @DisplayName("没有当前天或没有行程时的兜底")
    void fallbacksAreSafe() {
        LocalDate today = LocalDate.now();
        Trip trip = tripWith(today.plusDays(1), 3);

        assertThat(MessageParser.resolveTripDay(trip, null, "换掉户外景点").dayIndex()).isEqualTo(1);
        assertThat(MessageParser.resolveTripDay(null, 2, "明天换掉").dayIndex()).isEqualTo(1);
        // 没有结束日期时按 3 天处理
        Trip openEnded = Trip.builder().startDate(today.plusDays(1)).build();
        assertThat(MessageParser.resolveTripDay(openEnded, 9, "换掉").dayIndex()).isEqualTo(3);
        assertThat(MessageParser.tripDays(openEnded)).isEqualTo(3);
    }
}
