package com.travel.agent.util;

import com.travel.agent.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 日期/时间解析工具：把「格式对但日期不存在」这类输入（如 2026-02-30、2026-13-01）
 * 统一转成 400 业务异常，避免落到兜底 500。
 */
public final class Dates {

    private Dates() {
    }

    public static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, label + "不是有效日期，请使用 yyyy-MM-dd");
        }
    }

    public static LocalTime parseTime(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().length() == 5 ? value.trim() + ":00" : value.trim();
        try {
            return LocalTime.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, label + "不是有效时间，请使用 HH:mm");
        }
    }
}
