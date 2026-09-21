package com.travel.agent.repository;

import com.travel.agent.entity.HealthRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HealthRecordRepository extends JpaRepository<HealthRecord, Long> {
    List<HealthRecord> findByUserIdOrderByDateDesc(Long userId);
    Optional<HealthRecord> findByUserIdAndDate(Long userId, java.time.LocalDate date);
}
