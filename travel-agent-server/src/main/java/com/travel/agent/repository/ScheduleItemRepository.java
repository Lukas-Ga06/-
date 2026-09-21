package com.travel.agent.repository;

import com.travel.agent.entity.ScheduleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {
    List<ScheduleItem> findByUserIdOrderByDateAscTimeAscIdAsc(Long userId);
}
