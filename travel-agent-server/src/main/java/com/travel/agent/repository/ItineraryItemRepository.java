package com.travel.agent.repository;

import com.travel.agent.entity.ItineraryItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {
    List<ItineraryItem> findByTripIdOrderByDayIndexAscStartTimeAsc(Long tripId);

    /** 行程条目分页：单行程条目量受行程天数约束，这里用 DB limit 兜底响应体积 */
    List<ItineraryItem> findByTripId(Long tripId, Pageable pageable);

    void deleteByTripId(Long tripId);
}
