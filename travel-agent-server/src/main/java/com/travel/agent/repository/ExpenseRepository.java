package com.travel.agent.repository;

import com.travel.agent.entity.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByTripIdOrderBySpentAtDescIdDesc(Long tripId);

    List<Expense> findByUserIdOrderBySpentAtDescIdDesc(Long userId);

    /** 行程账单分页：排序由 Pageable 的 Sort 决定（spent_at desc, id desc） */
    Page<Expense> findByTripId(Long tripId, Pageable pageable);

    /** 用户最近账单分页（记账总览的「最近记录」） */
    Page<Expense> findByUserId(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /** 单行程支出合计（SUM 下推，避免把该行程全部账单读进内存再累加） */
    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.tripId = :tripId")
    BigDecimal sumAmountByTripId(@Param("tripId") Long tripId);

    /** 用户全部支出合计 */
    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.userId = :userId")
    BigDecimal sumAmountByUserId(@Param("userId") Long userId);

    /** 用户某日期区间的支出合计（本月支出） */
    @Query("select coalesce(sum(e.amount), 0) from Expense e "
            + "where e.userId = :userId and e.spentAt between :from and :to")
    BigDecimal sumAmountByUserIdAndSpentAtBetween(@Param("userId") Long userId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /** 分类维度聚合（GROUP BY 下推；金额大的在前，保证输出顺序稳定） */
    @Query("select e.category as category, sum(e.amount) as total from Expense e "
            + "where e.userId = :userId group by e.category order by sum(e.amount) desc")
    List<CategoryTotal> sumByCategory(@Param("userId") Long userId);

    /** 支付方式维度聚合 */
    @Query("select e.paymentMethod as paymentMethod, sum(e.amount) as total from Expense e "
            + "where e.userId = :userId group by e.paymentMethod order by sum(e.amount) desc")
    List<PaymentMethodTotal> sumByPaymentMethod(@Param("userId") Long userId);

    /** 行程维度聚合（孤儿账单在服务层归入「已删除的行程」分组） */
    @Query("select e.tripId as tripId, sum(e.amount) as total from Expense e "
            + "where e.userId = :userId group by e.tripId order by sum(e.amount) desc")
    List<TripTotal> sumByTripId(@Param("userId") Long userId);

    /** 删除行程时级联清理其账单，避免出现「挂在已删除行程上、界面里删不掉」的孤儿记录 */
    void deleteByTripId(Long tripId);

    interface CategoryTotal {
        String getCategory();

        BigDecimal getTotal();
    }

    interface PaymentMethodTotal {
        String getPaymentMethod();

        BigDecimal getTotal();
    }

    interface TripTotal {
        Long getTripId();

        BigDecimal getTotal();
    }
}
