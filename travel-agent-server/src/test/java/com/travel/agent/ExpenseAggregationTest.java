package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ExpenseDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ExpenseRepository;
import com.travel.agent.repository.MemoRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.service.MemoService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import com.travel.agent.util.Paging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聚合下推回归：SUM / GROUP BY / COUNT / Pageable 下推数据库后，
 * 金额、条数、排序必须与旧「全量加载 + 内存统计」的结果逐项一致，
 * 且对外 JSON 结构不变（batch9-contract：总览响应形状不许改）。
 */
class ExpenseAggregationTest extends IntegrationTestSupport {

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private TripService tripService;

    @Autowired
    private MemoService memoService;

    @Autowired
    private MemoRepository memoRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private AgentEngine agentEngine;

    /** 未关联行程分组在 overview 里的 tripId（服务层约定，负数不会与自增主键冲突） */
    private static final long UNLINKED_TRIP_ID = -1L;

    private Trip createTrip(long userId, String name) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName(name);
        request.setDestination("上海");
        request.setBudget(new BigDecimal("5000"));
        return tripService.create(userId, request);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("记账总览：SUM/GROUP BY 下推后金额与条数不变，分项之和仍等于总支出")
    void overviewDownpushMatchesOriginalAggregates() throws Exception {
        Account account = registerNew();
        Trip tripA = createTrip(account.id(), "行程A");
        Trip tripB = createTrip(account.id(), "行程B");

        LocalDate today = LocalDate.now();
        LocalDate lastMonth = today.minusMonths(1);
        expenseService.add(tripA.getId(), account.id(), "餐饮", amount("45.50"), "午餐", today, "现金");
        expenseService.add(tripA.getId(), account.id(), "交通", amount("30.00"), "打车", today, "支付宝");
        expenseService.add(tripB.getId(), account.id(), "住宿", amount("200.00"), "酒店", today, "银行卡");
        expenseService.add(tripB.getId(), account.id(), "餐饮", amount("24.50"), "早餐", lastMonth, "微信支付");

        ExpenseDtos.ExpenseOverview overview = expenseService.overview(account.id());

        // 旧实现：totalSpent = 全部金额累加；monthSpent = 本月（YearMonth.now()）累加；count = 全部条数
        assertThat(overview.getTotalSpent()).isEqualByComparingTo(amount("300.00"));
        assertThat(overview.getMonthSpent()).isEqualByComparingTo(amount("275.50"));
        assertThat(overview.getCount()).isEqualTo(4);

        assertThat(overview.getByCategory().get("餐饮")).isEqualByComparingTo(amount("70.00"));
        assertThat(overview.getByCategory().get("住宿")).isEqualByComparingTo(amount("200.00"));
        assertThat(overview.getByCategory().get("交通")).isEqualByComparingTo(amount("30.00"));

        assertThat(overview.getByPaymentMethod().get("银行卡")).isEqualByComparingTo(amount("200.00"));
        assertThat(overview.getByPaymentMethod().get("现金")).isEqualByComparingTo(amount("45.50"));
        assertThat(overview.getByPaymentMethod().get("支付宝")).isEqualByComparingTo(amount("30.00"));
        assertThat(overview.getByPaymentMethod().get("微信支付")).isEqualByComparingTo(amount("24.50"));

        // 按行程分项：金额降序，且分项之和 == 总支出（老回归点：孤儿账单也不能漏）
        assertThat(overview.getByTrip()).hasSize(2);
        assertThat(overview.getByTrip().get(0).getTripId()).isEqualTo(tripB.getId());
        assertThat(overview.getByTrip().get(0).getTotal()).isEqualByComparingTo(amount("224.50"));
        assertThat(overview.getByTrip().get(1).getTripId()).isEqualTo(tripA.getId());
        assertThat(overview.getByTrip().get(1).getTotal()).isEqualByComparingTo(amount("75.50"));
        BigDecimal breakdownSum = overview.getByTrip().stream()
                .map(ExpenseDtos.TripSpend::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(breakdownSum).isEqualByComparingTo(overview.getTotalSpent());

        // 最近 8 条：旧实现按 spent_at desc, id desc 取前 8（这里共 4 条，全部返回）
        List<ExpenseDtos.ExpenseDTO> recent = overview.getRecent();
        assertThat(recent).hasSize(4);
        assertThat(recent.get(0).getAmount()).isEqualByComparingTo(amount("200.00"));
        assertThat(recent.get(1).getAmount()).isEqualByComparingTo(amount("30.00"));
        assertThat(recent.get(2).getAmount()).isEqualByComparingTo(amount("45.50"));
        assertThat(recent.get(3).getAmount()).isEqualByComparingTo(amount("24.50"));
        assertThat(recent.get(0).getTripName()).isEqualTo("行程B");
        assertThat(recent.get(3).getSpentAt()).isEqualTo(lastMonth.toString());
    }

    @Test
    @DisplayName("记账总览 JSON 结构与旧版一致，且不带 pagination 键")
    void overviewJsonShapeUnchanged() throws Exception {
        Account account = registerNew();
        Trip trip = createTrip(account.id(), "结构测试");
        expenseService.add(trip.getId(), account.id(), "餐饮", amount("45.50"), "午餐", LocalDate.now(), "现金");

        mockMvc.perform(get("/api/expenses/overview").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSpent").value(45.50))
                .andExpect(jsonPath("$.data.monthSpent").value(45.50))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$['data']['byCategory']['餐饮']").value(45.50))
                .andExpect(jsonPath("$['data']['byPaymentMethod']['现金']").value(45.50))
                .andExpect(jsonPath("$.data.byTrip[0].tripId").value(trip.getId()))
                .andExpect(jsonPath("$.data.byTrip[0].tripName").value("结构测试"))
                .andExpect(jsonPath("$.data.byTrip[0].total").value(45.50))
                .andExpect(jsonPath("$.data.recent[0].tripName").value("结构测试"))
                .andExpect(jsonPath("$.pagination").doesNotExist());
    }

    @Test
    @DisplayName("totalOf 用 SUM 查询：与逐条累加一致，无账单返回 0")
    void totalOfMatchesSumQuery() throws Exception {
        Account account = registerNew();
        Trip trip = createTrip(account.id(), "汇总测试");
        Trip emptyTrip = createTrip(account.id(), "空行程");

        expenseService.add(trip.getId(), account.id(), "餐饮", amount("45.50"), null, LocalDate.now(), "现金");
        expenseService.add(trip.getId(), account.id(), "交通", amount("30.00"), null, LocalDate.now(), "现金");
        expenseService.add(trip.getId(), account.id(), "购物", amount("24.50"), null, LocalDate.now(), "现金");

        assertThat(expenseService.totalOf(trip.getId())).isEqualByComparingTo(amount("100.00"));
        assertThat(expenseRepository.sumAmountByTripId(trip.getId())).isEqualByComparingTo(amount("100.00"));
        assertThat(expenseService.totalOf(emptyTrip.getId())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("repository 级 Page：total 正确、排序下推、offset 精确（非整页倍数）")
    void expensePageKeepsTotalAndSortOrder() throws Exception {
        Account account = registerNew();
        Trip trip = createTrip(account.id(), "分页测试");
        LocalDate today = LocalDate.now();
        BigDecimal[] amounts = {amount("100.00"), amount("200.00"), amount("300.00"),
                amount("400.00"), amount("500.00")};
        for (int i = 0; i < amounts.length; i++) {
            // 依次是 今天-4 .. 今天，金额随时间递增
            expenseService.add(trip.getId(), account.id(), "餐饮", amounts[i], null,
                    today.minusDays(4L - i), "现金");
        }

        Sort sort = Sort.by(Sort.Order.desc("spentAt"), Sort.Order.desc("id"));
        Page<Expense> firstPage = expenseRepository.findByTripId(trip.getId(), Paging.pageable(0, 2, sort));
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getContent().get(0).getAmount()).isEqualByComparingTo(amount("500.00"));
        assertThat(firstPage.getContent().get(1).getAmount()).isEqualByComparingTo(amount("400.00"));

        // offset=1 不是 pageSize(2) 的整数倍：必须精确返回第 2、3 条，而不是取整回第 1 页
        Page<Expense> shifted = expenseRepository.findByTripId(trip.getId(), Paging.pageable(1, 2, sort));
        assertThat(shifted.getTotalElements()).isEqualTo(5);
        assertThat(shifted.getContent()).hasSize(2);
        assertThat(shifted.getContent().get(0).getAmount()).isEqualByComparingTo(amount("400.00"));
        assertThat(shifted.getContent().get(1).getAmount()).isEqualByComparingTo(amount("300.00"));

        Page<Expense> lastPage = expenseRepository.findByTripId(trip.getId(), Paging.pageable(4, 2, sort));
        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.getContent().get(0).getAmount()).isEqualByComparingTo(amount("100.00"));
    }

    @Test
    @DisplayName("备忘待办数量用 COUNT：toggle 后与 countByUserIdAndDoneFalse 一致")
    void memoOpenCountUsesCountQuery() throws Exception {
        Account account = registerNew();
        Memo first = memoService.add(account.id(), "备忘A");
        memoService.add(account.id(), "备忘B");
        memoService.add(account.id(), "备忘C");

        assertThat(memoService.openCount(account.id())).isEqualTo(3);
        memoService.toggle(account.id(), first.getId());
        assertThat(memoService.openCount(account.id())).isEqualTo(2);
        assertThat(memoRepository.countByUserIdAndDoneFalse(account.id())).isEqualTo(2);

        Page<Memo> page = memoService.page(account.id(), 0, 2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        // done asc：未完成在前
        assertThat(page.getContent().get(0).isDone()).isFalse();
    }

    @Test
    @DisplayName("没有当前行程也能记账：总览计入、归入「未关联行程」，分项之和仍等于总支出")
    void unlinkedExpenseIsCountedAndGroupedSeparately() throws Exception {
        Account account = registerNew();
        Trip trip = createTrip(account.id(), "有行程");

        expenseService.add(trip.getId(), account.id(), "交通", amount("30.00"), "打车", LocalDate.now(), "支付宝");
        Expense unlinked = expenseService.add(null, account.id(), "餐饮", amount("150.00"),
                "今天买咖啡", LocalDate.now(), "现金");
        assertThat(unlinked.getTripId()).isNull();

        ExpenseDtos.ExpenseOverview overview = expenseService.overview(account.id());

        assertThat(overview.getTotalSpent()).isEqualByComparingTo(amount("180.00"));
        assertThat(overview.getMonthSpent()).isEqualByComparingTo(amount("180.00"));
        assertThat(overview.getCount()).isEqualTo(2);

        ExpenseDtos.TripSpend unlinkedSpend = overview.getByTrip().stream()
                .filter(t -> Long.valueOf(UNLINKED_TRIP_ID).equals(t.getTripId()))
                .findFirst().orElseThrow();
        assertThat(unlinkedSpend.getTripName()).isEqualTo("未关联行程");
        assertThat(unlinkedSpend.getTotal()).isEqualByComparingTo(amount("150.00"));
        assertThat(overview.getByTrip()).noneMatch(t -> "已删除的行程".equals(t.getTripName()));

        BigDecimal breakdownSum = overview.getByTrip().stream()
                .map(ExpenseDtos.TripSpend::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(breakdownSum).isEqualByComparingTo(overview.getTotalSpent());

        // 最近记录：同一天按 id 倒序，后写入的未关联账目在前
        assertThat(overview.getRecent()).hasSize(2);
        assertThat(overview.getRecent().get(0).getTripName()).isEqualTo("未关联行程");
        assertThat(overview.getRecent().get(1).getTripName()).isEqualTo("有行程");

        // 行程预算口径不变：未关联账目不进任何行程
        assertThat(expenseService.totalOf(trip.getId())).isEqualByComparingTo(amount("30.00"));
        assertThat(expenseService.totalOf(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("「未关联行程」与「已删除的行程」是两个分组：不混组，分项之和仍等于总支出")
    void unlinkedExpenseIsNotMergedWithDeletedTrip() throws Exception {
        Account account = registerNew();
        Trip doomed = createTrip(account.id(), "将被删的行程");
        expenseService.add(doomed.getId(), account.id(), "餐饮", amount("45.00"), null,
                LocalDate.now(), "现金");
        // 模拟历史遗留数据：绕过级联清理直接删除行程，账单仍在
        tripRepository.deleteById(doomed.getId());
        expenseService.add(null, account.id(), "餐饮", amount("30.00"), null, LocalDate.now(), "现金");

        ExpenseDtos.ExpenseOverview overview = expenseService.overview(account.id());

        assertThat(overview.getTotalSpent()).isEqualByComparingTo(amount("75.00"));
        assertThat(overview.getByTrip()).hasSize(2);
        assertThat(overview.getByTrip())
                .anyMatch(t -> Long.valueOf(0L).equals(t.getTripId()) && "已删除的行程".equals(t.getTripName()));
        assertThat(overview.getByTrip())
                .anyMatch(t -> Long.valueOf(UNLINKED_TRIP_ID).equals(t.getTripId())
                        && "未关联行程".equals(t.getTripName()));
        BigDecimal breakdownSum = overview.getByTrip().stream()
                .map(ExpenseDtos.TripSpend::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(breakdownSum).isEqualByComparingTo(overview.getTotalSpent());
    }

    @Test
    @DisplayName("无当前行程时说「今天买咖啡花了 150」也能落库，并回复未关联行程文案")
    void recordExpenseWithoutTripPersistsGeneralLedgerEntry() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = agentEngine.handle(agentContext(account, "今天买咖啡花了 150"));

        assertThat(result.getIntent()).isEqualTo(IntentResult.RECORD_EXPENSE);
        assertThat(result.getMessage())
                .contains("✅ 已记账：餐饮 ¥150")
                .contains("（" + LocalDate.now() + " · 现金）")
                .contains("这笔未关联行程");
        List<Expense> saved = expenseRepository.findByUserIdOrderBySpentAtDescIdDesc(account.id());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTripId()).isNull();
        assertThat(saved.get(0).getCategory()).isEqualTo("餐饮");
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo(amount("150"));
    }

    @Test
    @DisplayName("删除未关联账单：delete(null, id) 不 NPE，deleteOwned 按用户归属校验")
    void deleteUnlinkedExpenseIsNullSafeAndOwned() throws Exception {
        Account account = registerNew();
        Expense saved = expenseService.add(null, account.id(), "餐饮", amount("12.00"), null,
                LocalDate.now(), "现金");

        assertThatThrownBy(() -> expenseService.deleteOwned(saved.getId(), account.id() + 9999))
                .isInstanceOf(BusinessException.class);
        assertThat(expenseRepository.findById(saved.getId())).isPresent();

        expenseService.delete(null, saved.getId());
        assertThat(expenseRepository.findById(saved.getId())).isEmpty();
    }

    private static AgentContext agentContext(Account account, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage(message);
        return ctx;
    }
}
