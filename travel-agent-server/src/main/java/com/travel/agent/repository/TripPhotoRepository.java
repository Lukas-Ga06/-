package com.travel.agent.repository;

import com.travel.agent.entity.TripPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripPhotoRepository extends JpaRepository<TripPhoto, Long> {
    List<TripPhoto> findByTripIdOrderByCreatedAtDesc(Long tripId);

    void deleteByTripId(Long tripId);
}
