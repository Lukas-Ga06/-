package com.travel.agent.service;

import com.travel.agent.dto.BudgetDtos;
import com.travel.agent.entity.Trip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 预算执行：校验行程归属后，汇总「计划成本 / 预算 / 实际支出」 */
@Service
@RequiredArgsConstructor
public class BudgetExecutionService {

    private final TripService tripService;
    private final ItineraryService itineraryService;
    private final ExpenseService expenseService;
    private final BudgetService budgetService;

    public BudgetDtos.BudgetExecution forTrip(Long tripId, Long userId) {
        Trip trip = tripService.getOwned(tripId, userId);
        return budgetService.execution(trip,
                itineraryService.listEntities(tripId),
                expenseService.listEntitiesByTrip(tripId));
    }
}
