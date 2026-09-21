package com.travel.agent.repository;

import com.travel.agent.entity.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {
    List<DailyReport> findByUserIdOrderByDateDescIdDesc(Long userId);
    Optional<DailyReport> findByUserIdAndDate(Long userId, java.time.LocalDate date);
}
