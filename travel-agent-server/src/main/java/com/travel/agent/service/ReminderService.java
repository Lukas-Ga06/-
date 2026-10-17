package com.travel.agent.service;

import com.travel.agent.dto.ReminderDtos;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 提醒板：由当前时间**实时计算**，不依赖后台调度器与推送服务。
 * 前端轮询本接口即可；用户点「知道了」后写入 dismissedAt，提醒不再出现。
 *
 * 规则：
 * - 日程：开始前 30 分钟出现，事件结束后 6 小时内仍可见（标记为已过期）；无具体时间的日程按当天 09:00 处理。
 * - 行程：出发前一天 09:00 出现，持续到出发日 23:59；已取消的行程不提醒。
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    public static final int SCHEDULE_LEAD_MINUTES = 30;
    private static final LocalTime ALL_DAY_TIME = LocalTime.of(9, 0);
    private static final LocalTime DEPARTURE_REMIND_TIME = LocalTime.of(9, 0);
    private static final int SCHEDULE_GRACE_HOURS = 6;

    private final ScheduleItemRepository scheduleRepository;
    private final TripRepository tripRepository;
    private final ScheduleService scheduleService;
    private final TripService tripService;

    public ReminderDtos.ReminderBoard board(Long userId) {
        return board(userId, LocalDateTime.now());
    }

    /** now 可注入，便于测试固定时间 */
    public ReminderDtos.ReminderBoard board(Long userId, LocalDateTime now) {
        List<ReminderDtos.Reminder> items = new ArrayList<>();

        for (ScheduleItem schedule : scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(userId)) {
            if (schedule.isDone() || schedule.getReminderDismissedAt() != null) {
                continue;
            }
            LocalDateTime eventAt = schedule.getDate().atTime(
                    schedule.getTime() == null ? ALL_DAY_TIME : schedule.getTime());
            LocalDateTime remindAt = eventAt.minusMinutes(SCHEDULE_LEAD_MINUTES);
            if (now.isBefore(remindAt) || now.isAfter(eventAt.plusHours(SCHEDULE_GRACE_HOURS))) {
                continue;
            }
            String timeText = schedule.getTime() == null
                    ? "全天"
                    : schedule.getTime().toString().substring(0, 5);
            String detail = schedule.getDate() + " " + timeText;
            if (schedule.getNote() != null && !schedule.getNote().isBlank()) {
                detail += " · " + schedule.getNote();
            }
            items.add(ReminderDtos.Reminder.builder()
                    .type("SCHEDULE")
                    .refId(schedule.getId())
                    .title(schedule.getTitle())
                    .detail(detail)
                    .remindAt(remindAt)
                    .eventAt(eventAt)
                    .overdue(now.isAfter(eventAt))
                    .minutesUntil(Duration.between(now, eventAt).toMinutes())
                    .build());
        }

        for (Trip trip : tripRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (Trip.STATUS_CANCELLED.equals(trip.getStatus())
                    || trip.getStartDate() == null
                    || trip.getDepartureReminderDismissedAt() != null) {
                continue;
            }
            LocalDateTime eventAt = trip.getStartDate().atTime(DEPARTURE_REMIND_TIME);
            LocalDateTime remindAt = eventAt.minusDays(1);
            LocalDateTime windowEnd = trip.getStartDate().atTime(23, 59);
            if (now.isBefore(remindAt) || now.isAfter(windowEnd)) {
                continue;
            }
            long days = Duration.between(now.toLocalDate().atStartOfDay(), eventAt).toDays();
            String detail = trip.getStartDate() + " 出发 · " + trip.totalDays() + " 天"
                    + (days <= 0 ? " · 今天出发" : " · 还有 " + days + " 天");
            items.add(ReminderDtos.Reminder.builder()
                    .type("TRIP")
                    .refId(trip.getId())
                    .title("出发提醒：" + (trip.getName() == null ? "行程" : trip.getName()))
                    .detail(detail)
                    .remindAt(remindAt)
                    .eventAt(eventAt)
                    .overdue(now.isAfter(trip.getStartDate().atTime(23, 59)))
                    .minutesUntil(Duration.between(now, eventAt).toMinutes())
                    .build());
        }

        // 按「事件发生时间」排序：马上要做的事排前面（而不是按提醒触发时刻，避免昨天触发的提醒压过 10 分钟后的会议）
        items.sort(Comparator.comparing(ReminderDtos.Reminder::getEventAt));
        return ReminderDtos.ReminderBoard.builder()
                .items(items)
                .count(items.size())
                .serverTime(now)
                .build();
    }

    public void dismissSchedule(Long userId, Long scheduleId) {
        scheduleService.dismissReminder(userId, scheduleId);
    }

    public void dismissTrip(Long userId, Long tripId) {
        tripService.dismissDepartureReminder(tripId, userId);
    }
}
