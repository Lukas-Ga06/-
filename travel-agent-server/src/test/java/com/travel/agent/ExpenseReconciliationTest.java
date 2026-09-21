package com.travel.agent;

import com.travel.agent.dto.ExpenseDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记账总览的可对账性：各行程分项之和必须等于总支出。
 * 回归背景：旧实现只在行程仍存在时累加 byTrip，行程删除后的账单会"消失在分项里却仍在总计里"。
 */
class ExpenseReconciliationTest extends IntegrationTestSupport {

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private TripService tripService;

    @Autowired
    private TripRepository tripRepository;

    @Test
    @DisplayName("行程被删除后遗留的账单仍计入分项（归入「已删除的行程」）")
    void orphanedExpenseKeepsBreakdownConsistent() throws Exception {
        Account account = registerNew();
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("对账测试");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        Trip trip = tripService.create(account.id(), request);

        expenseService.add(trip.getId(), account.id(), "餐饮", BigDecimal.valueOf(45),
                "午餐", LocalDate.now(), "现金");
        // 模拟历史遗留数据：绕过级联清理直接删除行程
        tripRepository.deleteById(trip.getId());

        ExpenseDtos.ExpenseOverview overview = expenseService.overview(account.id());

        assertThat(overview.getTotalSpent()).isEqualByComparingTo(new BigDecimal("45"));
        BigDecimal breakdownSum = overview.getByTrip().stream()
                .map(ExpenseDtos.TripSpend::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(breakdownSum).isEqualByComparingTo(overview.getTotalSpent());
        assertThat(overview.getByTrip()).anyMatch(t -> "已删除的行程".equals(t.getTripName()));
    }
}
