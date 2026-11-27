package com.travel.agent.service.ocr;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一次发票识别的结果字段。
 *
 * <p>只放「能从发票上读到的原始事实」：金额 / 商户 / 开票日期 / 原始文本。
 * {@code category} 是平台自己的记账分类，由 {@link OcrInvoiceService} 按商户 + 原文归类后填入，
 * 解析器不猜分类（同样的字面在不同上下文里可能落到不同类目）。
 *
 * @param amount      价税合计金额；识别不出为 {@code null}（由服务层报失败，绝不用 0 或估值代替）
 * @param merchant    销售方名称；识别不出为 {@code null}
 * @param invoiceDate 开票日期；识别不出为 {@code null}（记账日期回落为当天）
 * @param category    记账分类（餐饮 / 交通 / 住宿 / 门票 / 购物 / 其他），解析阶段为 {@code null}
 * @param rawText     识别摘要（商品名 + 商户 + 省份/城市/发票类型，不含密码区与校验码）：报销时做同城匹配，并过滤截断后写进确认前的 Diff 供核对
 */
public record InvoiceFields(BigDecimal amount, String merchant, LocalDate invoiceDate,
                            String category, String rawText) {
}