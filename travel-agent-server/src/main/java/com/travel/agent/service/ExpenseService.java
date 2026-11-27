package com.travel.agent.service;

import com.travel.agent.dto.ExpenseDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ExpenseRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.util.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    /** 行程已被删除的账单归到这个分组，保证「分项之和 == 总支出」 */
    private static final Long ORPHAN_TRIP_ID = 0L;
    private static final String ORPHAN_TRIP_NAME = "已删除的行程";

    /**
     * 未关联行程的通用账目单独成组：它和「行程已删除」是两回事，
     * 混进同一组会让分项语义失真（账单从没挂过行程，被说成行程被删）。
     * 负数键不会与自增主键冲突。
     */
    private static final Long UNLINKED_TRIP_ID = -1L;
    private static final String UNLINKED_TRIP_NAME = "未关联行程";

    /** 记账总览「最近记录」条数（响应结构的一部分，不要改） */
    private static final int RECENT_LIMIT = 8;

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

    /**
     * 行程账单分页（DB 层分页，不再先全量查询再内存切片）。
     * 排序与旧实现一致：spent_at desc, id desc。
     */
    public Page<ExpenseDtos.ExpenseDTO> page(Long tripId, int offset, int limit) {
        Pageable pageable = Paging.pageable(offset, limit,
                Sort.by(Sort.Order.desc("spentAt"), Sort.Order.desc("id")));
        return expenseRepository.findByTripId(tripId, pageable).map(ExpenseDtos.ExpenseDTO::from);
    }

    public Expense findById(Long expenseId) {
        return expenseRepository.findById(expenseId)
                .orElseThrow(() -> new BusinessException(404, "支出记录不存在"));
    }

    /** 单行程支出合计：SUM 下推数据库（每轮对话 / 每次 PDF 导出都会调用，不能全量加载） */
    public BigDecimal totalOf(Long tripId) {
        BigDecimal sum = expenseRepository.sumAmountByTripId(tripId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Transactional
    public Expense add(Long tripId, Long userId, String category, BigDecimal amount, String note,
                       LocalDate spentAt, String paymentMethod) {
        return add(tripId, null, userId, category, amount, note, spentAt, paymentMethod);
    }

    /** 记账落库：tripId / scheduleItemId 都可空（未关联行程的通用账目） */
    @Transactional
    public Expense add(Long tripId, Long scheduleItemId, Long userId, String category, BigDecimal amount,
                       String note, LocalDate spentAt, String paymentMethod) {
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
                .scheduleItemId(scheduleItemId)
                .userId(userId)
                .category(cat)
                .amount(amount)
                .note(note == null || note.isBlank() ? null : note.trim())
                .spentAt(spentAt == null ? LocalDate.now() : spentAt)
                .paymentMethod(pay)
                .build();
        return expenseRepository.save(expense);
    }

    /** 行程内账单删除：tripId 为空时不得 NPE（Objects.equals 处理 null） */
    @Transactional
    public void delete(Long tripId, Long expenseId) {
        Expense expense = expenseRepository.findById(expenseId)
                .filter(e -> Objects.equals(e.getTripId(), tripId))
                .orElseThrow(() -> new BusinessException(404, "支出记录不存在"));
        expenseRepository.delete(expense);
    }

    /** 按用户归属删除：未关联行程的账单没有行程可校验，只能比 user_id */
    @Transactional
    public void deleteOwned(Long expenseId, Long userId) {
        Expense expense = expenseRepository.findById(expenseId)
                .filter(e -> Objects.equals(e.getUserId(), userId))
                .orElseThrow(() -> new BusinessException(404, "支出记录不存在"));
        expenseRepository.delete(expense);
    }

    /**
     * 跨行程记账总览：合计/本月/支付方式/分类/按行程/最近记录。
     *
     * 第九批改为聚合下推（batch9-contract：列表与聚合不得全量加载）：
     * - 合计 / 本月合计 / 条数 → SUM / COUNT；
     * - 支付方式 / 分类 / 行程维度 → GROUP BY 聚合；
     * - 最近记录 → Pageable(0, 8) 单独查询。
     * 响应 JSON 结构（ExpenseOverview 的字段与含义）保持不变。
     */
    public ExpenseDtos.ExpenseOverview overview(Long userId) {
        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Map<Long, Trip> tripMap = trips.stream().collect(Collectors.toMap(Trip::getId, Function.identity()));

        BigDecimal totalSpent = nonNull(expenseRepository.sumAmountByUserId(userId));
        YearMonth now = YearMonth.now();
        BigDecimal monthSpent = nonNull(expenseRepository.sumAmountByUserIdAndSpentAtBetween(
                userId, now.atDay(1), now.atEndOfMonth()));
        long count = expenseRepository.countByUserId(userId);

        Map<String, BigDecimal> byPayment = new LinkedHashMap<>();
        for (ExpenseRepository.PaymentMethodTotal row : expenseRepository.sumByPaymentMethod(userId)) {
            // 历史数据的支付方式可能为空，旧实现按「现金」归类，这里保持一致
            byPayment.merge(row.getPaymentMethod() == null ? "现金" : row.getPaymentMethod(),
                    nonNull(row.getTotal()), BigDecimal::add);
        }

        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (ExpenseRepository.CategoryTotal row : expenseRepository.sumByCategory(userId)) {
            byCategory.merge(row.getCategory(), nonNull(row.getTotal()), BigDecimal::add);
        }

        Map<Long, BigDecimal> byTrip = new LinkedHashMap<>();
        for (ExpenseRepository.TripTotal row : expenseRepository.sumByTripId(userId)) {
            // 关键：三类归属分开计入，否则分项加起来对不上 totalSpent，或语义被合并失真：
            //   null trip_id → 未关联行程；行程已删除 → 0；其余 → 真实行程 id
            Long tripKey;
            if (row.getTripId() == null) {
                tripKey = UNLINKED_TRIP_ID;
            } else if (tripMap.containsKey(row.getTripId())) {
                tripKey = row.getTripId();
            } else {
                tripKey = ORPHAN_TRIP_ID;
            }
            byTrip.merge(tripKey, nonNull(row.getTotal()), BigDecimal::add);
        }

        List<ExpenseDtos.TripSpend> tripSpends = byTrip.entrySet().stream()
                .map(en -> ExpenseDtos.TripSpend.builder()
                        .tripId(en.getKey())
                        .tripName(tripNameFor(en.getKey(), tripMap))
                        .total(en.getValue())
                        .build())
                .sorted((a, b) -> b.getTotal().compareTo(a.getTotal()))
                .toList();

        Pageable recentPage = Paging.pageable(0, RECENT_LIMIT,
                Sort.by(Sort.Order.desc("spentAt"), Sort.Order.desc("id")));
        List<ExpenseDtos.ExpenseDTO> recent = expenseRepository.findByUserId(userId, recentPage)
                .getContent().stream()
                .map(dto -> {
                    ExpenseDtos.ExpenseDTO d = ExpenseDtos.ExpenseDTO.from(dto);
                    if (dto.getTripId() == null) {
                        d.setTripName(UNLINKED_TRIP_NAME);
                    } else {
                        Trip t = tripMap.get(dto.getTripId());
                        d.setTripName(t == null ? null : t.getName());
                    }
                    return d;
                })
                .toList();

        return ExpenseDtos.ExpenseOverview.builder()
                .totalSpent(totalSpent)
                .monthSpent(monthSpent)
                .count(count)
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

    private static String tripNameFor(Long tripKey, Map<Long, Trip> tripMap) {
        if (UNLINKED_TRIP_ID.equals(tripKey)) {
            return UNLINKED_TRIP_NAME;
        }
        Trip trip = tripMap.get(tripKey);
        return trip == null ? ORPHAN_TRIP_NAME : trip.getName();
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
