package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 记账记录：个人实际花费。trip_id 为空表示未关联行程的通用账目 */
@Entity
@Table(name = "expense", indexes = {
        @Index(name = "idx_expense_trip", columnList = "trip_id"),
        @Index(name = "idx_expense_user", columnList = "user_id"),
        @Index(name = "idx_expense_schedule", columnList = "schedule_item_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

    public static final String CAT_FOOD = "餐饮";
    public static final String CAT_TRANSPORT = "交通";
    public static final String CAT_HOTEL = "住宿";
    public static final String CAT_TICKET = "门票";
    public static final String CAT_SHOPPING = "购物";
    public static final String CAT_OTHER = "其他";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联行程；为空 = 未关联行程的通用账目 */
    @Column(name = "trip_id")
    private Long tripId;

    /** 预留：报销/账期条目（schedule_item.id），暂无写入方 */
    @Column(name = "schedule_item_id")
    private Long scheduleItemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 30, nullable = false)
    private String category;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(length = 200)
    private String note;

    @Column(name = "spent_at", nullable = false)
    private LocalDate spentAt;

    /** 支付渠道：微信支付 / 支付宝 / 现金 / 银行卡 */
    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
