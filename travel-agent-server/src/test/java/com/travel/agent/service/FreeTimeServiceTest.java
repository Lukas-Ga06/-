package com.travel.agent.service;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空闲时段纯计算：不启 Spring，直接用实体构造占用来源。
 * 口径见 docs/guide/capability-boundaries.md：窗口 08:00–22:00、缺结束时间按 1 小时、
 * 无时间日程不阻塞、重复日程在查询范围内展开。
 */
class FreeTimeServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 28);

    private static ScheduleItem schedule(LocalDate date, LocalTime time, String title) {
        return ScheduleItem.builder().userId(1L).title(title).date(date).time(time).done(false).build();
    }

    private static ScheduleItem recurringSchedule(LocalDate date, LocalTime time, String recurrence, LocalDate until) {
        return ScheduleItem.builder().userId(1L).title("晨会").date(date).time(time)
                .recurrence(recurrence).repeatUntil(until).done(false).build();
    }

    private static Trip trip(long id, LocalDate start, LocalDate end) {
        return Trip.builder().id(id).userId(1L).name("上海").destination("上海")
                .startDate(start).endDate(end).build();
    }

    private static Trip cancelledTrip(long id, LocalDate start, LocalDate end) {
        return Trip.builder().id(id).userId(1L).name("上海").destination("上海")
                .startDate(start).endDate(end).status(Trip.STATUS_CANCELLED).build();
    }

    private static ItineraryItem itinerary(long tripId, LocalDate date, int dayIndex,
                                           LocalTime start, LocalTime end, String title) {
        return ItineraryItem.builder().tripId(tripId).dayIndex(dayIndex).itemType("attraction").title(title)
                .startTime(LocalDateTime.of(date, start))
                .endTime(end == null ? null : LocalDateTime.of(date, end))
                .build();
    }

    private static FreeTimeService.FreeTimeReport report(LocalDate from, LocalDate to, int minutes,
                                                         List<ScheduleItem> schedules, List<Trip> trips,
                                                         List<ItineraryItem> itineraryItems) {
        Map<Long, List<ItineraryItem>> byTrip = itineraryItems.stream()
                .collect(Collectors.groupingBy(ItineraryItem::getTripId));
        return FreeTimeService.compute(from, to, minutes, schedules, trips, byTrip);
    }

    private static List<String> slots(FreeTimeService.FreeTimeReport report, int dayIndex) {
        return report.days().get(dayIndex).free().stream()
                .map(slot -> slot.start() + "–" + slot.end())
                .toList();
    }

    @Test
    @DisplayName("重叠与相接的占用合并后取窗口补集")
    void mergesOverlappingAndAdjacentBusy() {
        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60,
                List.of(schedule(DAY, LocalTime.of(9, 0), "评审会"),
                        schedule(DAY, LocalTime.of(9, 30), "讨论"),
                        schedule(DAY, LocalTime.of(10, 30), "例会")),
                List.of(), List.of());

        assertThat(slots(report, 0)).containsExactly("08:00–09:00", "11:30–22:00");
        assertThat(report.days().get(0).busy()).hasSize(3);
    }

    @Test
    @DisplayName("日程只有开始时间时按 1 小时估算占用")
    void scheduleWithoutEndTimeOccupiesOneHour() {
        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60,
                List.of(schedule(DAY, LocalTime.of(9, 0), "评审会")), List.of(), List.of());

        assertThat(slots(report, 0)).containsExactly("08:00–09:00", "10:00–22:00");
        assertThat(report.days().get(0).busy().get(0).end()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("没有具体时间的日程不阻塞，只列为未计入占用")
    void untimedScheduleDoesNotBlock() {
        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60,
                List.of(schedule(DAY, null, "取快递")), List.of(), List.of());

        assertThat(slots(report, 0)).containsExactly("08:00–22:00");
        assertThat(report.untimedSchedules()).containsExactly("取快递");
    }

    @Test
    @DisplayName("行程条目按 dayIndex 落到当天，缺结束时间按 1 小时估算")
    void itineraryItemWithoutEndTimeOccupiesOneHour() {
        Trip trip = trip(10L, DAY.minusDays(1), DAY.plusDays(2));
        ItineraryItem item = itinerary(10L, DAY, 2, LocalTime.of(14, 0), null, "外滩");

        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60, List.of(), List.of(trip), List.of(item));

        assertThat(slots(report, 0)).containsExactly("08:00–14:00", "15:00–22:00");
        assertThat(report.days().get(0).busy().get(0).title()).isEqualTo("外滩");
    }

    @Test
    @DisplayName("行程只在自身日期范围内占用（endDate 为空只覆盖出发当天）")
    void tripOnlyBlocksDatesWithinItsRange() {
        Trip singleDay = trip(20L, DAY, null);
        ItineraryItem item = itinerary(20L, DAY, 1, LocalTime.of(9, 0), LocalTime.of(10, 0), "外滩");

        FreeTimeService.FreeTimeReport nextDay = report(DAY.plusDays(1), DAY.plusDays(1), 60,
                List.of(), List.of(singleDay), List.of(item));

        assertThat(slots(nextDay, 0)).containsExactly("08:00–22:00");
    }

    @Test
    @DisplayName("已取消的行程不占用空闲时段")
    void cancelledTripDoesNotBlock() {
        Trip cancelled = cancelledTrip(30L, DAY, DAY);
        ItineraryItem item = itinerary(30L, DAY, 1, LocalTime.of(8, 0), LocalTime.of(22, 0), "已取消的行程");

        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60, List.of(), List.of(cancelled), List.of(item));

        assertThat(slots(report, 0)).containsExactly("08:00–22:00");
        assertThat(report.days().get(0).busy()).isEmpty();
    }

    @Test
    @DisplayName("全天被占满时空闲与建议槽位都为空")
    void fullyBookedDayHasNoFreeSlots() {
        Trip trip = trip(10L, DAY, DAY);
        ItineraryItem item = itinerary(10L, DAY, 1, LocalTime.of(8, 0), LocalTime.of(22, 0), "全天培训");

        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60, List.of(), List.of(trip), List.of(item));

        assertThat(report.days().get(0).free()).isEmpty();
        assertThat(report.days().get(0).suggestions()).isEmpty();
    }

    @Test
    @DisplayName("建议槽位从空闲段起点按 30 分钟向上对齐（30 分钟会议）")
    void suggestsThirtyMinuteSlot() {
        Trip trip = trip(10L, DAY, DAY);
        ItineraryItem item = itinerary(10L, DAY, 1, LocalTime.of(8, 0), LocalTime.of(8, 50), "早餐");

        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 30, List.of(), List.of(trip), List.of(item));

        assertThat(slots(report, 0)).containsExactly("08:50–22:00");
        assertThat(report.days().get(0).suggestions()).singleElement()
                .satisfies(slot -> {
                    assertThat(slot.start()).isEqualTo(LocalTime.of(9, 0));
                    assertThat(slot.end()).isEqualTo(LocalTime.of(9, 30));
                });
    }

    @Test
    @DisplayName("建议槽位从空闲段起点按 30 分钟向上对齐（1 小时会议）")
    void suggestsOneHourSlot() {
        Trip trip = trip(10L, DAY, DAY);
        ItineraryItem item = itinerary(10L, DAY, 1, LocalTime.of(8, 0), LocalTime.of(8, 50), "早餐");

        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60, List.of(), List.of(trip), List.of(item));

        assertThat(report.days().get(0).suggestions()).singleElement()
                .satisfies(slot -> {
                    assertThat(slot.start()).isEqualTo(LocalTime.of(9, 0));
                    assertThat(slot.end()).isEqualTo(LocalTime.of(10, 0));
                });
    }

    @Test
    @DisplayName("22:00 边界与窗口外占用：只保留窗口内的部分")
    void clipsBusyToWindow() {
        FreeTimeService.FreeTimeReport report = report(DAY, DAY, 60,
                List.of(schedule(DAY, LocalTime.of(6, 0), "早班车"),
                        schedule(DAY, LocalTime.of(21, 30), "夜会")),
                List.of(), List.of());

        assertThat(slots(report, 0)).containsExactly("08:00–21:30");
        assertThat(report.days().get(0).busy()).singleElement()
                .satisfies(block -> assertThat(block.end()).isEqualTo(LocalTime.of(22, 0)));
    }

    @Test
    @DisplayName("多日范围每天一行，逐日给出空闲")
    void multiDayRangeReportsEachDay() {
        FreeTimeService.FreeTimeReport report = report(DAY, DAY.plusDays(2), 60,
                List.of(), List.of(), List.of());

        assertThat(report.days()).extracting(FreeTimeService.DayFreeTime::date)
                .containsExactly(DAY, DAY.plusDays(1), DAY.plusDays(2));
        assertThat(report.days()).allSatisfy(day ->
                assertThat(day.free()).singleElement()
                        .satisfies(slot -> assertThat(slot.start()).isEqualTo(LocalTime.of(8, 0))));
    }

    @Test
    @DisplayName("查询范围最多 7 天：超出部分截断")
    void clampsRangeToSevenDays() {
        FreeTimeService.FreeTimeReport report = report(DAY, DAY.plusDays(9), 60,
                List.of(), List.of(), List.of());

        assertThat(report.days()).hasSize(FreeTimeService.MAX_RANGE_DAYS);
        assertThat(report.days().get(report.days().size() - 1).date())
                .isEqualTo(DAY.plusDays(FreeTimeService.MAX_RANGE_DAYS - 1L));
    }

    @Test
    @DisplayName("重复日程在查询范围内按重复规则展开，repeatUntil 之后不再出现")
    void expandsRecurringScheduleWithinRange() {
        ScheduleItem daily = recurringSchedule(DAY, LocalTime.of(9, 0), "DAILY", DAY.plusDays(1));

        FreeTimeService.FreeTimeReport report = report(DAY, DAY.plusDays(3), 60,
                List.of(daily), List.of(), List.of());

        assertThat(report.days().get(0).busy()).hasSize(1);
        assertThat(report.days().get(1).busy()).hasSize(1);
        assertThat(report.days().get(2).busy()).isEmpty();
        assertThat(report.days().get(3).busy()).isEmpty();
        assertThat(slots(report, 2)).containsExactly("08:00–22:00");
    }
}