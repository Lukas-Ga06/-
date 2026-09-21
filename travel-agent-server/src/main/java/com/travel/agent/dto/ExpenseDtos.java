package com.travel.agent.dto;

import com.travel.agent.entity.Expense;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class ExpenseDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpenseDTO {
        private Long id;
        private Long tripId;
        private String tripName;  // 仅总览接口填充
        private String category;
        private BigDecimal amount;
        private String note;
        private String paymentMethod;
        private String spentAt;   // yyyy-MM-dd
        private String createdAt; // yyyy-MM-dd HH:mm

        public static ExpenseDTO from(Expense e) {
            return ExpenseDTO.builder()
                    .id(e.getId())
                    .tripId(e.getTripId())
                    .category(e.getCategory())
                    .amount(e.getAmount())
                    .note(e.getNote())
                    .paymentMethod(e.getPaymentMethod())
                    .spentAt(e.getSpentAt() == null ? null : e.getSpentAt().toString())
                    .createdAt(e.getCreatedAt() == null ? null
                            : e.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddExpenseRequest {
        private String category;       // 餐饮/交通/住宿/门票/购物/其他
        private BigDecimal amount;
        private String note;
        private String spentAt;        // yyyy-MM-dd，缺省今天
        private String paymentMethod;  // 微信支付/支付宝/现金/银行卡，缺省现金
    }

    /** 跨行程记账总览（首页用） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpenseOverview {
        private BigDecimal totalSpent;
        private BigDecimal monthSpent;
        private long count;
        private Map<String, BigDecimal> byPaymentMethod;
        private Map<String, BigDecimal> byCategory;
        private List<TripSpend> byTrip;
        private List<ExpenseDTO> recent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripSpend {
        private Long tripId;
        private String tripName;
        private BigDecimal total;
    }
}
