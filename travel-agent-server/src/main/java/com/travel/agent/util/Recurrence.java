package com.travel.agent.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/** 日程重复规则：无重复 / 每天 / 每周 / 每月（start 为首次发生日期） */
public final class Recurrence {

    public static final String NONE = "NONE";
    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";

    private static final Set<String> ALL = Set.of(NONE, DAILY, WEEKLY, MONTHLY);

    private Recurrence() {
    }

    public static String normalize(String value) {
        return value != null && ALL.contains(value) ? value : NONE;
    }

    public static boolean isRecurring(String value) {
        return !NONE.equals(normalize(value));
    }

    public static String label(String value) {
        return switch (normalize(value)) {
            case DAILY -> "每天";
            case WEEKLY -> "每周";
            case MONTHLY -> "每月";
            default -> "不重复";
        };
    }

    /**
     * 从 from（含）起的下一次发生日期；序列已结束返回 null。
     * MONTHLY 采用"加一个月"的日历语义（1/31 → 2/28 → 3/28）。
     */
    public static LocalDate nextOnOrAfter(LocalDate start, String recurrence, LocalDate repeatUntil, LocalDate from) {
        if (start == null) {
            return null;
        }
        String rule = normalize(recurrence);
        if (NONE.equals(rule)) {
            return start.isBefore(from) ? null : start;
        }
        LocalDate date = start;
        int guard = 0;
        while (date.isBefore(from)) {
            if (repeatUntil != null && date.isAfter(repeatUntil)) {
                return null;
            }
            date = switch (rule) {
                case DAILY -> date.plusDays(1);
                case WEEKLY -> date.plusWeeks(1);
                default -> date.plusMonths(1);
            };
            if (++guard > 20000) {
                return null;
            }
        }
        return repeatUntil != null && date.isAfter(repeatUntil) ? null : date;
    }

    /**
     * 带时刻的"下一次发生"：如果今天就是发生日、但该时刻已经过去，则顺延到下一次。
     * 例：每天 09:00 的日程，在 18:50 查看时，下一次应是明天而不是今天。
     */
    public static LocalDate nextOccurrence(LocalDate start, String recurrence, LocalDate repeatUntil,
                                           LocalTime time, LocalDateTime now) {
        LocalDate from = now.toLocalDate();
        if (time != null && !now.toLocalTime().isBefore(time)) {
            from = from.plusDays(1);
        }
        return nextOnOrAfter(start, recurrence, repeatUntil, from);
    }
}
