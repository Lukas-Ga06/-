package com.travel.agent.repository;

import com.travel.agent.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByTripIdOrderBySpentAtDescIdDesc(Long tripId);

    List<Expense> findByUserIdOrderBySpentAtDescIdDesc(Long userId);
}
