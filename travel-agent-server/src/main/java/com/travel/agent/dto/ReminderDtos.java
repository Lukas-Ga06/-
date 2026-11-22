package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 提醒板：日程到点提醒 + 行程出发提醒 */
public class ReminderDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reminder {
        /** SCHEDULE / TRIP */
        private String type;
        private Long refId;
        private String title;
        private String detail;
        /** 提醒应该出现的时刻（日程=开始前 30 分钟，行程=出发前一天 09:00） */
        private LocalDateTime remindAt;
        /** 对应事件开始时间 */
        private LocalDateTime eventAt;
        /** 已过事件时间仍未处理 */
        private boolean overdue;
        /** 距离事件开始还有多少分钟（负数=已过） */
        private long minutesUntil;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReminderBoard {
        private List<Reminder> items;
        private int count;
        private LocalDateTime serverTime;
    }
}
