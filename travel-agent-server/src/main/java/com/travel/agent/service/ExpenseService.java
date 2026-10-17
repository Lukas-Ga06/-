package com.travel.agent.service;

import com.travel.agent.dto.ExpenseDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ExpenseRepository;
import com.travel.agent.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    /** 行程已被删除的账单归到这个分组，保证「分项之和 == 总支出」 */
    private static final Long ORPHAN_TRIP_ID = 0L;
    private static final String ORPHAN_TRIP_NAME = "已删除的行程";

    private static final Set<String> CATEGORIES = Set.of(
            Expense.CAT_FOOD, Expense.CAT_TRANSPORT, Expense.CAT_HOTEL,
            Expense.CAT_TICKET, Expense.CAT_SHOPPING, Expense.CAT_OTHER);
    private static final Set<String> PAYMENT_METHODS = Set.of("微信支付", "支付宝", "现金", "银行卡");

    private final ExpenseRepository expenseRepository;
    private final TripRepository tripRepository;

    public List<ExpenseDtos.ExpenseDTO> list(Long tripId) {
        return expenseRepository.findByTripIdOrderBySpentAtDescIdDesc(tripId).stream()
                .map(ExpenseDtos.ExpenseDTO::from)
                .toList();
    }

    public Expense findById(Long expenseId) {
        return expenseRepository.findById(expenseId)
                .orElseThrow(() -> new BusinessException(404, "支出记录不存在"));
    }

    public BigDecimal totalOf(Long tripId) {
        return expenseRepository.findByTripIdOrderBySpentAtDescIdDesc(tripId).stream()
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public Expense add(Long tripId, Long userId, String category, BigDecimal amount, String note,
                       LocalDate spentAt, String paymentMethod) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("金额必须大于 0");
        }
        String cat = category == null || category.isBlank() ? Expense.CAT_OTHER : category;
        if (!CATEGORIES.contains(cat)) {
            cat = Expense.CAT_OTHER;
        }
        String pay = paymentMethod == null || paymentMethod.isBlank() ? "现金" : paymentMethod;
        if (!PAYMENT_METHODS.contains(pay)) {
            pay = "现金";
        }
        Expense expense = Expense.builder()
                .tripId(tripId)
                .userId(userId)
                .category(cat)
                .amount(amount)
                .note(note == null || note.isBlank() ? null : note.trim())
                .spentAt(spentAt == null ? LocalDate.now() : spentAt)
                .paymentMethod(pay)
                .build();
        return expenseRepository.save(expense);
    }

    @Transactional
    public void delete(Long tripId, Long expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .filter(e -> e.getTripId().equals(tripId))
                .orElseThrow(() -> new BusinessException(404, "支出记录不存在"));
        expenseRepository.delete(expense);
    }

    /** 跨行程记账总览：合计/本月/支付方式/分类/按行程/最近记录 */
    public ExpenseDtos.ExpenseOverview overview(Long userId) {
        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Map<Long, Trip> tripMap = trips.stream().collect(Collectors.toMap(Trip::getId, Function.identity()));
        List<Expense> all = expenseRepository.findByUserIdOrderBySpentAtDescIdDesc(userId);

        BigDecimal totalSpent = BigDecimal.ZERO;
        BigDecimal monthSpent = BigDecimal.ZERO;
        YearMonth now = YearMonth.now();
        Map<String, BigDecimal> byPayment = new LinkedHashMap<>();
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        Map<Long, BigDecimal> byTrip = new LinkedHashMap<>();

        for (Expense e : all) {
            BigDecimal amount = e.getAmount() == null ? BigDecimal.ZERO : e.getAmount();
            totalSpent = totalSpent.add(amount);
            if (e.getSpentAt() != null && YearMonth.from(e.getSpentAt()).equals(now)) {
                monthSpent = monthSpent.add(amount);
            }
            byPayment.merge(e.getPaymentMethod() == null ? "现金" : e.getPaymentMethod(), amount, BigDecimal::add);
            byCategory.merge(e.getCategory(), amount, BigDecimal::add);
            // 关键：即使行程已被删除，也要计入分组，否则分项加起来对不上 totalSpent
            Long tripKey = e.getTripId() != null && tripMap.containsKey(e.getTripId())
                    ? e.getTripId() : ORPHAN_TRIP_ID;
            byTrip.merge(tripKey, amount, BigDecimal::add);
        }

        List<ExpenseDtos.TripSpend> tripSpends = byTrip.entrySet().stream()
                .map(en -> ExpenseDtos.TripSpend.builder()
                        .tripId(en.getKey())
                        .tripName(tripMap.containsKey(en.getKey())
                                ? tripMap.get(en.getKey()).getName() : ORPHAN_TRIP_NAME)
                        .total(en.getValue())
                        .build())
                .sorted((a, b) -> b.getTotal().compareTo(a.getTotal()))
                .toList();

        List<ExpenseDtos.ExpenseDTO> recent = all.stream().limit(8)
                .map(dto -> {
                    ExpenseDtos.ExpenseDTO d = ExpenseDtos.ExpenseDTO.from(dto);
                    Trip t = tripMap.get(dto.getTripId());
                    d.setTripName(t == null ? null : t.getName());
                    return d;
                })
                .toList();

        return ExpenseDtos.ExpenseOverview.builder()
                .totalSpent(totalSpent)
                .monthSpent(monthSpent)
                .count(all.size())
                .byPaymentMethod(byPayment)
                .byCategory(byCategory)
                .byTrip(tripSpends)
                .recent(recent)
                .build();
    }

    /** 供需要列表实体的调用方使用 */
    public List<Expense> listEntities(Long userId) {
        return new ArrayList<>(expenseRepository.findByUserIdOrderBySpentAtDescIdDesc(userId));
    }

    /** 某行程的全部账单实体（预算对账用） */
    public List<Expense> listEntitiesByTrip(Long tripId) {
        return new ArrayList<>(expenseRepository.findByTripIdOrderBySpentAtDescIdDesc(tripId));
    }
}
