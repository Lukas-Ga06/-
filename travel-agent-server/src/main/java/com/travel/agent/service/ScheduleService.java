package com.travel.agent.service;

import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.ScheduleItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.travel.agent.util.Recurrence;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleItemRepository repository;

    public List<ScheduleItem> list(Long userId) {
        List<ScheduleItem> items = repository.findByUserIdOrderByDateAscTimeAscIdAsc(userId);
        LocalDateTime now = LocalDateTime.now();
        items.forEach(item -> item.setNextOccurrence(item.isDone()
                ? null
                : Recurrence.nextOccurrence(item.getDate(), item.getRecurrence(), item.getRepeatUntil(),
                        item.getTime(), now)));
        return items;
    }

    public List<ScheduleItem> upcoming(Long userId) {
        return list(userId).stream()
                .filter(s -> !s.getDate().isBefore(LocalDate.now()))
                .limit(6)
                .toList();
    }

    /** 某用户某日期范围内（含首尾）的全部日程（含已完成）：批量清空与方案指纹共用 */
    public List<ScheduleItem> findInRange(Long userId, LocalDate from, LocalDate to) {
        return repository.findByUserIdAndDateBetweenOrderByDateAscTimeAscIdAsc(userId, from, to);
    }

    @Transactional
    public ScheduleItem add(Long userId, String title, LocalDate date, java.time.LocalTime time, String note) {
        return add(userId, title, date, time, note, Recurrence.NONE, null);
    }

    @Transactional
    public ScheduleItem add(Long userId, String title, LocalDate date, java.time.LocalTime time, String note,
                            String recurrence, LocalDate repeatUntil) {
        return add(userId, title, date, time, note, recurrence, repeatUntil, null, null);
    }

    /** P1：旅行相关日程带上 tripId / travelDay（可空），来源默认 AGENT */
    @Transactional
    public ScheduleItem add(Long userId, String title, LocalDate date, java.time.LocalTime time, String note,
                            String recurrence, LocalDate repeatUntil, Long tripId, Integer travelDay) {
        return repository.save(ScheduleItem.builder()
                .userId(userId)
                .title(title)
                .date(date)
                .time(time)
                .note(note)
                .recurrence(Recurrence.normalize(recurrence))
                .repeatUntil(repeatUntil)
                .tripId(tripId)
                .travelDay(travelDay)
                .source(tripId == null ? "MANUAL" : "AGENT")
                .build());
    }

    @Transactional
    public ScheduleItem toggle(Long userId, Long id) {
        ScheduleItem item = repository.findById(id)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new com.travel.agent.exception.BusinessException(404, "日程不存在"));
        item.setDone(!item.isDone());
        return repository.save(item);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        ScheduleItem item = repository.findById(id)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new com.travel.agent.exception.BusinessException(404, "日程不存在"));
        repository.delete(item);
    }

    /** 忽略该日程的提醒（不改变日程本身） */
    @Transactional
    public void dismissReminder(Long userId, Long id) {
        ScheduleItem item = repository.findById(id)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new com.travel.agent.exception.BusinessException(404, "日程不存在"));
        item.setReminderDismissedAt(java.time.LocalDateTime.now());
        repository.save(item);
    }
}
