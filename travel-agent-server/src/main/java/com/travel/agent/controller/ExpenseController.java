package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.ExpenseDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;
    private final TripService tripService;
    private final AuthService authService;

    @GetMapping("/api/trips/{tripId}/expenses")
    public ApiResponse<List<ExpenseDtos.ExpenseDTO>> list(@PathVariable Long tripId, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        tripService.getOwned(tripId, user.getId());
        return ApiResponse.ok(expenseService.list(tripId));
    }

    @PostMapping("/api/trips/{tripId}/expenses")
    public ApiResponse<ExpenseDtos.ExpenseDTO> add(@PathVariable Long tripId,
                                                   @RequestBody ExpenseDtos.AddExpenseRequest request,
                                                   Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        tripService.getOwned(tripId, user.getId());
        LocalDate spentAt = request.getSpentAt() == null || request.getSpentAt().isBlank()
                ? LocalDate.now() : LocalDate.parse(request.getSpentAt());
        Expense saved = expenseService.add(tripId, user.getId(), request.getCategory(),
                request.getAmount(), request.getNote(), spentAt, request.getPaymentMethod());
        return ApiResponse.ok(ExpenseDtos.ExpenseDTO.from(saved));
    }

    /** 首页记账总览：跨行程统计 + 最近记录 */
    @GetMapping("/api/expenses/overview")
    public ApiResponse<ExpenseDtos.ExpenseOverview> overview(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(expenseService.overview(user.getId()));
    }

    @DeleteMapping("/api/expenses/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        Expense expense = expenseService.findById(id);
        Trip trip = tripService.getOwned(expense.getTripId(), user.getId());
        expenseService.delete(trip.getId(), id);
        return ApiResponse.ok(null);
    }
}
