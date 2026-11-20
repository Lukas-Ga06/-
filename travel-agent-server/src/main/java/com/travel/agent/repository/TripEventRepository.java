package com.travel.agent.repository;

import com.travel.agent.entity.TripEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripEventRepository extends JpaRepository<TripEvent, Long> {

    List<TripEvent> findByTripIdOrderByOccurredAtDescIdDesc(Long tripId, Pageable pageable);

    long countByTripId(Long tripId);

    void deleteByTripId(Long tripId);
}
