package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 发票整理（粘贴发票文本 → LLM 提取结构化字段） */
@Entity
@Table(name = "invoice", indexes = {
        @Index(name = "idx_invoice_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 200)
    private String title;       // 发票抬头

    @Column(length = 200)
    private String vendor;      // 销售方/开票方

    /** 交通 / 餐饮 / 办公 / 住宿 / 其他 */
    @Column(length = 20)
    private String category;

    @Column(precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    /** 上传的原始文件（存于 travel.invoices.dir，可为空表示手工粘贴文本） */
    @Column(name = "source_file", length = 120)
    private String sourceFile;

    /** PDF / IMAGE / TEXT */
    @Column(name = "source_type", length = 20)
    private String sourceType;

    /** 已生成的记账记录；非空表示该发票已记过账，避免重复 */
    @Column(name = "expense_id")
    private Long expenseId;

    /** 记账时选择的行程 */
    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
