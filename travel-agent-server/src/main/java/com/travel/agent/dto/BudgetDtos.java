package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 预算执行口径：计划成本（行程项）/ 行程预算 / 实际支出（记账） */
public class BudgetDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryBudget {
        /** 统一到记账的分类口径：餐饮/交通/住宿/门票/购物/其他 */
        private String category;
        private BigDecimal planned;
        private BigDecimal actual;
    }

    /** 按天偏差：day=0 表示行程日期之外的支出（行前/行后采购，只有实际没有计划） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyBudget {
        /** 1..N 为行程第几天；0 = 行程外（日期缺失时也归 0） */
        private int day;
        private BigDecimal planned;
        private BigDecimal actual;
        /** 偏差 = actual - planned（正数=当天比计划多花） */
        private BigDecimal variance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetExecution {
        private Long tripId;
        private String tripName;
        /** 行程预算 */
        private BigDecimal budget;
        /** 计划成本：行程项 cost 合计 */
        private BigDecimal planned;
        /** 实际支出：记账合计 */
        private BigDecimal actual;
        /** 预算余额 = budget - actual（无预算时 null） */
        private BigDecimal remaining;
        /** 偏差 = actual - planned（正数=比计划多花） */
        private BigDecimal variance;
        /** 预算使用率 = actual / budget（无预算时 null） */
        private BigDecimal executionRate;
        private boolean overBudget;
        /** 计划本身是否已超预算 */
        private boolean planOverBudget;
        private List<CategoryBudget> categories;
        /** 按天偏差明细（有计划或有实际的天才会出现） */
        private List<DailyBudget> daily;
    }
}
