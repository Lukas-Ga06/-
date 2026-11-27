package com.travel.agent.service;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.util.Recurrence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读空闲时段查询：固定 08:00–22:00 窗口内，日程与行程条目占用之外的连续空档，
 * 并给出能放下指定时长会议的建议槽位。
 *
 * <p>占用口径与 {@code PlanInspector#endOf} 一致：只有开始时间的条目按 1 小时估算；
 * 没有具体时间的日程不阻塞，只在回答里单独列为「未计入占用」。
 * 计算全部是无状态静态方法（{@link #compute}），不依赖 Spring，可直接单测。
 */
@Service
@RequiredArgsConstructor
public class FreeTimeService {

    /** 空闲窗口：早 8 点到晚 10 点（既有一日安排的可视范围） */
    public static final LocalTime WINDOW_START = LocalTime.of(8, 0);
    public static final LocalTime WINDOW_END = LocalTime.of(22, 0);

    /** 建议槽位按 30 分钟对齐，每天最多 2 个 */
    public static final int SLOT_ALIGN_MINUTES = 30;
    public static final int MAX_SUGGESTIONS_PER_DAY = 2;

    /** 查询范围上限（天）：超出截断，避免一次渲染几十天的回答 */
    public static final int MAX_RANGE_DAYS = 7;

    /** 重复日程展开的循环上限（与 Recurrence 的迭代保护同量级） */
    static final int MAX_REPEAT_ITERATIONS = 500;

    private static final Comparator<BusyBlock> BY_START =
            Comparator.comparing(BusyBlock::start).thenComparing(BusyBlock::end);

    private final ScheduleItemRepository scheduleItemRepository;
    private final TripRepository tripRepository;
    private final ItineraryItemRepository itineraryItemRepository;

    /** 查询 [from, to] 内每天的空闲；只读，不落库。 */
    public FreeTimeReport query(Long userId, LocalDate from, LocalDate to, int meetingMinutes) {
        LocalDate lastDay = clampRangeEnd(from, to);
        List<ScheduleItem> schedules = scheduleItemRepository.findByUserIdOrderByDateAscTimeAscIdAsc(userId);
        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(trip -> coversAnyDay(trip, from, lastDay))
                .toList();
        Map<Long, List<ItineraryItem>> itineraryByTrip = new HashMap<>();
        for (Trip trip : trips) {
            itineraryByTrip.put(trip.getId(),
                    itineraryItemRepository.findByTripIdOrderByDayIndexAscStartTimeAsc(trip.getId()));
        }
        return compute(from, lastDay, meetingMinutes, schedules, trips, itineraryByTrip);
    }

    /**
     * 纯计算入口：不查库、不依赖 Spring。
     *
     * @param schedules      该用户的全部日程（含重复日程，内部按范围展开）
     * @param trips          该用户的行程
     * @param itineraryByTrip tripId → 行程条目
     */
    public static FreeTimeReport compute(LocalDate from, LocalDate to, int meetingMinutes,
                                         List<ScheduleItem> schedules, List<Trip> trips,
                                         Map<Long, List<ItineraryItem>> itineraryByTrip) {
        LocalDate lastDay = clampRangeEnd(from, to);
        Map<LocalDate, List<BusyBlock>> busyByDate = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(lastDay); day = day.plusDays(1)) {
            busyByDate.put(day, new ArrayList<>());
        }

        Set<String> untimedSchedules = new LinkedHashSet<>();
        for (ScheduleItem item : schedules == null ? List.<ScheduleItem>of() : schedules) {
            addSchedule(item, from, lastDay, busyByDate, untimedSchedules);
        }
        for (Trip trip : trips == null ? List.<Trip>of() : trips) {
            addTripItems(trip, from, lastDay, itineraryByTrip, busyByDate);
        }

        List<DayFreeTime> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<BusyBlock>> entry : busyByDate.entrySet()) {
            List<BusyBlock> busy = new ArrayList<>(entry.getValue());
            busy.sort(BY_START);
            List<FreeSlot> free = freeSlots(busy);
            days.add(new DayFreeTime(entry.getKey(), List.copyOf(busy), free,
                    suggestions(free, meetingMinutes)));
        }
        return new FreeTimeReport(meetingMinutes, WINDOW_START, WINDOW_END,
                List.copyOf(days), List.copyOf(untimedSchedules));
    }

    /**
     * 范围上限 7 天：多出来的日期直接截断（调用方文案不需要额外说明，超出范围的说法本就不存在）。
     */
    public static LocalDate clampRangeEnd(LocalDate from, LocalDate to) {
        LocalDate last = to == null || to.isBefore(from) ? from : to;
        if (ChronoUnit.DAYS.between(from, last) > MAX_RANGE_DAYS - 1L) {
            return from.plusDays(MAX_RANGE_DAYS - 1L);
        }
        return last;
    }

    /** 日程在查询范围内的所有发生日期；重复规则复用 {@link Recurrence#nextOnOrAfter}。 */
    private static List<LocalDate> occurrences(ScheduleItem item, LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate occurrence = Recurrence.nextOnOrAfter(item.getDate(), item.getRecurrence(),
                item.getRepeatUntil(), from);
        int guard = 0;
        while (occurrence != null && !occurrence.isAfter(to) && guard++ < MAX_REPEAT_ITERATIONS) {
            dates.add(occurrence);
            occurrence = Recurrence.nextOnOrAfter(item.getDate(), item.getRecurrence(),
                    item.getRepeatUntil(), occurrence.plusDays(1));
        }
        return dates;
    }

    private static void addSchedule(ScheduleItem item, LocalDate from, LocalDate to,
                                    Map<LocalDate, List<BusyBlock>> busyByDate, Set<String> untimedSchedules) {
        if (item == null || item.isDone() || item.getDate() == null) {
            return;
        }
        for (LocalDate day : occurrences(item, from, to)) {
            List<BusyBlock> busy = busyByDate.get(day);
            if (busy == null) {
                continue;
            }
            if (item.getTime() == null) {
                untimedSchedules.add(item.getTitle());
            } else {
                addBusy(busy, item.getTime(), scheduleEnd(item.getTime()), item.getTitle());
            }
        }
    }

    private static void addTripItems(Trip trip, LocalDate from, LocalDate to,
                                     Map<Long, List<ItineraryItem>> itineraryByTrip,
                                     Map<LocalDate, List<BusyBlock>> busyByDate) {
        // 取消的行程不再阻塞空闲时段：用户已放弃它，但 itinerary_item 可能还留在库里
        if (trip == null || Trip.STATUS_CANCELLED.equals(trip.getStatus())
                || trip.getId() == null || trip.getStartDate() == null || itineraryByTrip == null) {
            return;
        }
        List<ItineraryItem> items = itineraryByTrip.get(trip.getId());
        if (items == null || items.isEmpty()) {
            return;
        }
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!covers(trip, day)) {
                continue;
            }
            int dayIndex = (int) (ChronoUnit.DAYS.between(trip.getStartDate(), day) + 1);
            List<BusyBlock> busy = busyByDate.get(day);
            for (ItineraryItem item : items) {
                if (busy == null || item.getDayIndex() == null || item.getDayIndex() != dayIndex
                        || item.getStartTime() == null) {
                    continue;
                }
                addItineraryBusy(busy, item);
            }
        }
    }

    /** 只有开始时间的条目按 1 小时估算（与 PlanInspector#endOf 同口径）；跨天条目占用到窗口结束。 */
    private static void addItineraryBusy(List<BusyBlock> busy, ItineraryItem item) {
        LocalDateTime startDateTime = item.getStartTime();
        LocalTime start = startDateTime.toLocalTime();
        LocalTime end;
        if (item.getEndTime() == null) {
            end = start.plusHours(1);
        } else if (!item.getEndTime().isAfter(startDateTime)) {
            return;
        } else if (!item.getEndTime().toLocalDate().equals(startDateTime.toLocalDate())) {
            end = WINDOW_END;
        } else {
            end = item.getEndTime().toLocalTime();
        }
        if (!end.isAfter(start)) {
            end = WINDOW_END;
        }
        addBusy(busy, start, end, item.getTitle());
    }

    private static LocalTime scheduleEnd(LocalTime start) {
        LocalTime end = start.plusHours(1);
        return end.isAfter(start) ? end : WINDOW_END;
    }

    /** 裁剪到窗口并忽略完全落在窗口外的占用；22:00 之后与跨午夜的安排都不影响次日窗口。 */
    private static void addBusy(List<BusyBlock> busy, LocalTime start, LocalTime end, String title) {
        if (start == null || end == null || !end.isAfter(start)) {
            return;
        }
        LocalTime clippedStart = start.isBefore(WINDOW_START) ? WINDOW_START : start;
        LocalTime clippedEnd = end.isAfter(WINDOW_END) ? WINDOW_END : end;
        if (clippedEnd.isAfter(clippedStart)) {
            busy.add(new BusyBlock(clippedStart, clippedEnd, title));
        }
    }

    /** 合并重叠/相接的占用后取窗口补集（按开始时间升序扫描，cursor 始终是已占用的最右端）。 */
    private static List<FreeSlot> freeSlots(List<BusyBlock> busy) {
        List<FreeSlot> free = new ArrayList<>();
        LocalTime cursor = WINDOW_START;
        for (BusyBlock block : busy) {
            if (block.start().isAfter(cursor)) {
                free.add(new FreeSlot(cursor, block.start()));
            }
            if (block.end().isAfter(cursor)) {
                cursor = block.end();
            }
        }
        if (cursor.isBefore(WINDOW_END)) {
            free.add(new FreeSlot(cursor, WINDOW_END));
        }
        return free;
    }

    /** 每个空闲段从起点按 30 分钟向上对齐，剩余长度够 N 分钟就给一个建议；每天最多 2 个。 */
    private static List<Suggestion> suggestions(List<FreeSlot> free, int meetingMinutes) {
        List<Suggestion> suggestions = new ArrayList<>();
        for (FreeSlot slot : free) {
            LocalTime start = alignUp(slot.start());
            if (!start.isBefore(slot.end())
                    || Duration.between(start, slot.end()).toMinutes() < meetingMinutes) {
                continue;
            }
            suggestions.add(new Suggestion(start, start.plusMinutes(meetingMinutes)));
            if (suggestions.size() >= MAX_SUGGESTIONS_PER_DAY) {
                break;
            }
        }
        return suggestions;
    }

    private static LocalTime alignUp(LocalTime time) {
        LocalTime base = time.withSecond(0).withNano(0);
        int remainder = base.getMinute() % SLOT_ALIGN_MINUTES;
        return remainder == 0 ? base : base.plusMinutes(SLOT_ALIGN_MINUTES - remainder);
    }

    /** 行程覆盖某天：startDate <= date <= (endDate == null ? startDate : endDate) */
    private static boolean covers(Trip trip, LocalDate date) {
        if (trip.getStartDate() == null || date.isBefore(trip.getStartDate())) {
            return false;
        }
        LocalDate end = trip.getEndDate() == null ? trip.getStartDate() : trip.getEndDate();
        return !date.isAfter(end);
    }

    private static boolean coversAnyDay(Trip trip, LocalDate from, LocalDate to) {
        if (trip == null || trip.getStartDate() == null) {
            return false;
        }
        LocalDate end = trip.getEndDate() == null ? trip.getStartDate() : trip.getEndDate();
        return !end.isBefore(from) && !trip.getStartDate().isAfter(to);
    }

    /** 一段被占用的时间（已裁剪到窗口），title 用于回答里列出占用条目。 */
    public record BusyBlock(LocalTime start, LocalTime end, String title) {
    }

    /** 一段连续空闲。 */
    public record FreeSlot(LocalTime start, LocalTime end) {
        public long minutes() {
            return Duration.between(start, end).toMinutes();
        }
    }

    /** 能放下指定会议时长的建议槽位。 */
    public record Suggestion(LocalTime start, LocalTime end) {
    }

    /** 单日结果：占用、空闲、建议槽位。 */
    public record DayFreeTime(LocalDate date, List<BusyBlock> busy, List<FreeSlot> free,
                              List<Suggestion> suggestions) {
    }

    /** 整次查询结果：窗口、每日结果、以及未计入占用的无时间日程。 */
    public record FreeTimeReport(int meetingMinutes, LocalTime windowStart, LocalTime windowEnd,
                                 List<DayFreeTime> days, List<String> untimedSchedules) {
    }
}