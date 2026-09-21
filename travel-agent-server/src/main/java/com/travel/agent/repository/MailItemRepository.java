package com.travel.agent.repository;

import com.travel.agent.entity.MailItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MailItemRepository extends JpaRepository<MailItem, Long> {
    List<MailItem> findByUserIdOrderByCreatedAtDesc(Long userId);
}
