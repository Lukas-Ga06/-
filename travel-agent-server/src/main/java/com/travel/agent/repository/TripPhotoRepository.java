package com.travel.agent.repository;

import com.travel.agent.entity.TripPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TripPhotoRepository extends JpaRepository<TripPhoto, Long> {
    List<TripPhoto> findByTripIdOrderByCreatedAtDesc(Long tripId);

    void deleteByTripId(Long tripId);

    long countByTripId(Long tripId);

    /** 单个用户已占用的照片字节数（配额检查用） */
    @Query("select coalesce(sum(p.sizeBytes), 0) from TripPhoto p where p.userId = :userId")
    long totalBytesOfUser(@Param("userId") Long userId);
}
