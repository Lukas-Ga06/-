package com.travel.agent.service;

import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 发票 → 账单的候选关联（纯查询，不写库）。
 *
 * <p>两条规则都刻意保守，<b>宁可不关联也不猜</b>：
 * <ul>
 *   <li><b>日程</b>：该用户该日、未完成的第一条（有时间优先）——报销通常对应一次出差安排；</li>
 *   <li><b>行程</b>：该用户覆盖该日期、且目的地出现在识别原文里的行程（同城才关联）。
 *       不同城就不自动关联：把一张杭州餐费挂到北京行程上，行程预算会被算错。</li>
 * </ul>
 * 结果里 trip / schedule 任一为空都表示「未关联」，由用户在确认门前一眼看到（Diff 的 detail）。
 */
@Service
@RequiredArgsConstructor
public class InvoiceMatchService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 候选关联；没有候选时对应字段为 null（三处都为 null = 未关联任何对象） */
    public record InvoiceCandidates(Long scheduleItemId, String scheduleLabel, Long tripId, String tripLabel) {

        public static InvoiceCandidates none() {
            return new InvoiceCandidates(null, null, null, null);
        }
    }

    private final ScheduleItemRepository scheduleItemRepository;
    private final TripRepository tripRepository;

    /**
     * 按「日期 + 识别原文」找候选关联。
     *
     * @param userId 当前用户（一切查询按它隔离）
     * @param date   账单日期（发票开票日期；识别不出时由调用方回落当天）
     * @param text   识别商户 + 原文，用于判断行程目的地是否出现在票面上
     */
    public InvoiceCandidates match(Long userId, LocalDate date, String text) {
        if (userId == null || date == null) {
            return InvoiceCandidates.none();
        }
        String haystack = text == null ? "" : text;
        ScheduleItem schedule = pickSchedule(userId, date);
        Trip trip = pickTrip(userId, date, haystack);
        return new InvoiceCandidates(
                schedule == null ? null : schedule.getId(), scheduleLabel(schedule),
                trip == null ? null : trip.getId(), tripLabel(trip));
    }

    /** 当天未完成日程：优先第一条有时间（Diff 里能看出是哪件事），否则第一条 */
    private ScheduleItem pickSchedule(Long userId, LocalDate date) {
        List<ScheduleItem> items = scheduleItemRepository
                .findByUserIdAndDateBetweenOrderByDateAscTimeAscIdAsc(userId, date, date);
        ScheduleItem first = null;
        for (ScheduleItem item : items) {
            if (item.isDone()) {
                continue;
            }
            if (first == null) {
                first = item;
            }
            if (item.getTime() != null) {
                // 时间列的排序在两种数据库下对 NULL 的处置不同，这里按「有没有时间」显式挑
                return item;
            }
        }
        return first;
    }

    /** 覆盖该日期、且目的地出现在票面上的行程；多条取创建时间最新的一条（查询已按 createdAt desc） */
    private Trip pickTrip(Long userId, LocalDate date, String text) {
        for (Trip trip : tripRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (trip.getStartDate() == null || trip.getDestination() == null || trip.getDestination().isBlank()) {
                continue;
            }
            LocalDate end = trip.getEndDate() == null ? trip.getStartDate() : trip.getEndDate();
            if (date.isBefore(trip.getStartDate()) || date.isAfter(end)) {
                continue;
            }
            if (text.contains(trip.getDestination())) {
                return trip;
            }
        }
        return null;
    }

    private static String scheduleLabel(ScheduleItem item) {
        if (item == null) {
            return null;
        }
        return item.getTime() == null ? item.getTitle() : item.getTime().format(HH_MM) + " " + item.getTitle();
    }

    private static String tripLabel(Trip trip) {
        if (trip == null) {
            return null;
        }
        String name = trip.getName() == null || trip.getName().isBlank() ? trip.getDestination() : trip.getName();
        return name + "（" + trip.getDestination() + "）";
    }
}