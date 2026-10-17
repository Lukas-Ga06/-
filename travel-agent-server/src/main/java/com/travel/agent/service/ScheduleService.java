package com.travel.agent.service;

import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.ScheduleItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleItemRepository repository;

    public List<ScheduleItem> list(Long userId) {
        return repository.findByUserIdOrderByDateAscTimeAscIdAsc(userId);
    }

    public List<ScheduleItem> upcoming(Long userId) {
        return list(userId).stream()
                .filter(s -> !s.getDate().isBefore(LocalDate.now()))
                .limit(6)
                .toList();
    }

    @Transactional
    public ScheduleItem add(Long userId, String title, LocalDate date, java.time.LocalTime time, String note) {
        return repository.save(ScheduleItem.builder()
                .userId(userId)
                .title(title)
                .date(date)
                .time(time)
                .note(note)
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
