package com.travel.agent.repository;

import com.travel.agent.entity.MeetingMinutes;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingMinutesRepository extends JpaRepository<MeetingMinutes, Long> {
    List<MeetingMinutes> findByUserIdOrderByCreatedAtDesc(Long userId);
}
