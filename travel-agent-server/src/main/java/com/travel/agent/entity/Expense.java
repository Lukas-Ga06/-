package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 行程支出记录：个人记账（实际花费），与行程预算对比 */
@Entity
@Table(name = "expense")
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

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

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
