package com.travel.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一条待落库的账单草稿（批次 3：发票报销）。
 *
 * <p>属于「方案里的操作项」，不是已落库的账单：先随 {@code AgentPlan} 序列化进 {@code pending_plan}，
 * 用户确认后由 {@code ExecutorAgent.commit} 逐字段转给
 * {@code ExpenseService.add(tripId, scheduleItemId, userId, category, amount, note, spentAt, paymentMethod)}。
 *
 * <p>为什么单独定义一个 DTO 而不是复用 {@code Expense} 实体：草稿没有 id、没有 userId（提交时才绑定），
 * 也不应带着 JPA 的持久化语义在方案 JSON 里来回序列化。
 *
 * @param tripId          关联行程（同城匹配才有值；null = 未关联行程的通用账目）
 * @param scheduleItemId  关联日程（同日未完成日程；null = 不关联）
 * @param category        记账分类（餐饮 / 交通 / 住宿 / 门票 / 购物 / 其他）
 * @param amount          金额（> 0）；为空或非正数时提交会被 ExpenseService 拒绝
 * @param note            备注（发票报销用商户名）
 * @param spentAt         消费日期（发票开票日期；识别不出时回落当天）
 * @param paymentMethod   支付方式（发票没有支付渠道信息，默认现金）
 */
public record ExpenseDraft(Long tripId, Long scheduleItemId, String category, BigDecimal amount,
                           String note, LocalDate spentAt, String paymentMethod) {
}