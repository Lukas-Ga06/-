package com.travel.agent.repository;

import com.travel.agent.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByTripIdOrderBySpentAtDescIdDesc(Long tripId);

    List<Expense> findByUserIdOrderBySpentAtDescIdDesc(Long userId);

    /** 删除行程时级联清理其账单，避免出现「挂在已删除行程上、界面里删不掉」的孤儿记录 */
    void deleteByTripId(Long tripId);
}
