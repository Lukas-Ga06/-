package com.travel.agent.repository;

import com.travel.agent.entity.ItineraryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {
    List<ItineraryItem> findByTripIdOrderByDayIndexAscStartTimeAsc(Long tripId);
    void deleteByTripId(Long tripId);
}
