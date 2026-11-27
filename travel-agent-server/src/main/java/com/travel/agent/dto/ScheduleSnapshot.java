package com.travel.agent.dto;

import com.travel.agent.entity.ScheduleItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 日程快照：确认方案里的日程原值，也是撤销历史里 before_state 的序列化单元。
 *
 * <p>为什么放 dto 而不是 entity/agent：agent 的 {@link com.travel.agent.agent.PlanOperation}
 * 与 service 的 {@code OperationHistoryService} 都要读写它，service 不能反向依赖 agent，
 * 而 entity 只该表达表结构、不做跨层传输。
 *
 * <p>{@code userId} 不参与快照：撤销永远由当前登录用户发起，还原时按调用方给的 userId 落库，
 * 不还原历史归属。
 */
public record ScheduleSnapshot(
        Long id,
        LocalDate date,
        LocalTime time,
        String title,
        String note,
        String recurrence,
        LocalDate repeatUntil,
        Long tripId,
        Long itineraryItemId,
        Integer travelDay,
        String source,
        String sourceKey,
        boolean done,
        LocalDateTime reminderDismissedAt,
        LocalDateTime createdAt) {

    public static ScheduleSnapshot of(ScheduleItem item) {
        if (item == null) {
            return null;
        }
        return new ScheduleSnapshot(item.getId(), item.getDate(), item.getTime(), item.getTitle(),
                item.getNote(), item.getRecurrence(), item.getRepeatUntil(), item.getTripId(),
                item.getItineraryItemId(), item.getTravelDay(), item.getSource(), item.getSourceKey(),
                item.isDone(), item.getReminderDismissedAt(), item.getCreatedAt());
    }

    /** 还原成可落库的实体：id 置空（撤销是重新插入一条，不复用已删除的主键） */
    public ScheduleItem toEntity(Long userId) {
        return ScheduleItem.builder()
                .userId(userId)
                .title(title)
                .date(date)
                .time(time)
                .note(note)
                .recurrence(recurrence)
                .repeatUntil(repeatUntil)
                .tripId(tripId)
                .itineraryItemId(itineraryItemId)
                .travelDay(travelDay)
                .source(source)
                .sourceKey(sourceKey)
                .done(done)
                .reminderDismissedAt(reminderDismissedAt)
                // @PrePersist 只在为空时补当前时间：快照带原值，撤销后创建时间不漂移
                .createdAt(createdAt)
                .build();
    }
}
