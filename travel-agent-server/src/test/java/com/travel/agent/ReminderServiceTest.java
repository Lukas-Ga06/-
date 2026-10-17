package com.travel.agent;

import com.travel.agent.dto.ReminderDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ReminderService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提醒板：日程提前 30 分钟、行程出发前一天 09:00；
 * 已完成/已取消/已忽略的不提醒；过了事件时间标记 overdue。
 */
class ReminderServiceTest extends IntegrationTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 10, 0);

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private TripService tripService;

    private ScheduleItem addSchedule(long userId, LocalDate date, LocalTime time, String title) {
        return scheduleService.add(userId, title, date, time, null);
    }

    private Trip newTrip(long userId, LocalDate start) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("杭州行");
        request.setDestination("杭州");
        request.setStartDate(start.toString());
        request.setEndDate(start.plusDays(2).toString());
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private List<ReminderDtos.Reminder> items(long userId) {
        return reminderService.board(userId, NOW).getItems();
    }

    @Test
    @DisplayName("日程：30 分钟内开始的出现在提醒板，2 小时后的不出现")
    void scheduleReminderLeadWindow() throws Exception {
        Account account = registerNew();
        addSchedule(account.id(), NOW.toLocalDate(), LocalTime.of(10, 20), "组会");
        addSchedule(account.id(), NOW.toLocalDate(), LocalTime.of(12, 30), "下午的会");

        List<ReminderDtos.Reminder> items = items(account.id());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTitle()).isEqualTo("组会");
        assertThat(items.get(0).getType()).isEqualTo("SCHEDULE");
        assertThat(items.get(0).isOverdue()).isFalse();
        assertThat(items.get(0).getMinutesUntil()).isEqualTo(20);
    }

    @Test
    @DisplayName("日程：已过时间（6 小时内）标记 overdue；已完成或已忽略的不再提醒")
    void scheduleReminderOverdueAndDismiss() throws Exception {
        Account account = registerNew();
        ScheduleItem overdue = addSchedule(account.id(), NOW.toLocalDate(), LocalTime.of(9, 0), "已过期的会");
        ScheduleItem done = addSchedule(account.id(), NOW.toLocalDate(), LocalTime.of(10, 10), "已完成的会");
        scheduleService.toggle(account.id(), done.getId());

        List<ReminderDtos.Reminder> items = items(account.id());
        assertThat(items).extracting(ReminderDtos.Reminder::getTitle).containsExactly("已过期的会");
        assertThat(items.get(0).isOverdue()).isTrue();

        reminderService.dismissSchedule(account.id(), overdue.getId());
        assertThat(items(account.id())).isEmpty();
    }

    @Test
    @DisplayName("重复日程：按下一次发生时间提醒，已结束的系列不提醒")
    void recurringScheduleUsesNextOccurrence() throws Exception {
        Account account = registerNew();
        LocalDate today = NOW.toLocalDate();
        // 每两周一次，上一次是两周前 → 下一次正好是今天 10:20
        scheduleService.add(account.id(), "周会", today.minusWeeks(2), LocalTime.of(10, 20), null, "WEEKLY", null);
        // 每周一次，上一次是昨天 → 下一次在 6 天后，不提醒
        scheduleService.add(account.id(), "下周的会", today.minusDays(1), LocalTime.of(10, 20), null, "WEEKLY", null);
        // 每周一次但已过结束日期
        scheduleService.add(account.id(), "已结束的会", today.minusWeeks(2), LocalTime.of(10, 20), null,
                "WEEKLY", today.minusWeeks(1));

        List<ReminderDtos.Reminder> items = items(account.id());

        assertThat(items).extracting(ReminderDtos.Reminder::getTitle).containsExactly("周会");
        assertThat(items.get(0).getDetail()).contains("每周");
        assertThat(items.get(0).getMinutesUntil()).isEqualTo(20);
    }

    @Test
    @DisplayName("行程：出发前一天 09:00 起提醒；太远的行程不提醒")
    void tripDepartureReminderWindow() throws Exception {
        Account account = registerNew();
        Trip tomorrow = newTrip(account.id(), NOW.toLocalDate().plusDays(1));
        newTrip(account.id(), NOW.toLocalDate().plusDays(10));

        List<ReminderDtos.Reminder> items = items(account.id());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getType()).isEqualTo("TRIP");
        assertThat(items.get(0).getRefId()).isEqualTo(tomorrow.getId());
        assertThat(items.get(0).getTitle()).contains("出发提醒");
        assertThat(items.get(0).getDetail()).contains("还有 1 天");
    }

    @Test
    @DisplayName("已取消或已忽略的行程不再提醒")
    void cancelledOrDismissedTripIsNotReminded() throws Exception {
        Account account = registerNew();
        Trip cancelled = newTrip(account.id(), NOW.toLocalDate().plusDays(1));
        tripService.updateStatus(cancelled.getId(), account.id(), Trip.STATUS_CANCELLED);
        assertThat(items(account.id())).isEmpty();

        Trip dismissed = newTrip(account.id(), NOW.toLocalDate().plusDays(1));
        tripService.dismissDepartureReminder(dismissed.getId(), account.id());
        assertThat(items(account.id())).isEmpty();
    }

    @Test
    @DisplayName("提醒接口只返回自己的数据，忽略他人提醒返回 404")
    void remindersAreScopedToOwner() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        ScheduleItem item = addSchedule(owner.id(), NOW.toLocalDate(), LocalTime.of(10, 20), "组会");

        mockMvc.perform(get("/api/reminders").header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reminders/schedule/" + item.getId() + "/dismiss")
                        .header("Authorization", intruder.bearer()))
                .andExpect(status().isNotFound());
    }
}
