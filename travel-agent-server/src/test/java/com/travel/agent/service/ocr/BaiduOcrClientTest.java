package com.travel.agent.service.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 百度发票识别响应的纯解析单测：不发任何网络请求（R6）。
 *
 * <p>钉死三件事：
 * <ol>
 *   <li>金额取价税合计 {@code AmountInFiguers}，缺失时回落 {@code TotalAmount}；</li>
 *   <li>商户与开票日期按实际票面文本解析（{@code 2026年11月20日} 与 {@code 2026-11-20} 都认）；</li>
 *   <li>{@code error_code} 非 0 一律抛 {@link OcrException}，不把错误响应当成一张票。</li>
 * </ol>
 */
class BaiduOcrClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("测试样例 JSON 不合法", e);
        }
    }

    @Test
    @DisplayName("完整票面：金额取价税合计、商户与中文日期与商品原文都能解析")
    void parsesFullInvoice() {
        JsonNode response = json("""
                {"log_id": 1, "words_result": {
                  "AmountInFiguers": "128.50",
                  "TotalAmount": "113.72",
                  "SellerName": "上海某某餐饮有限公司",
                  "InvoiceDate": "2026年11月20日",
                  "CommodityName": [{"word": "*餐饮服务*餐费"}],
                  "InvoiceNum": "12345678"
                }}
                """);

        InvoiceFields fields = BaiduOcrClient.parseResponse(response);

        assertThat(fields.amount()).isEqualByComparingTo(new BigDecimal("128.50"));
        assertThat(fields.merchant()).isEqualTo("上海某某餐饮有限公司");
        assertThat(fields.invoiceDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        // 类目由 OcrInvoiceService 按商户 + 原文归类（共用记账词表），解析器不猜
        assertThat(fields.category()).isNull();
        assertThat(fields.rawText())
                .contains("餐饮服务")
                .contains("上海某某餐饮有限公司")
                .contains("InvoiceNum=12345678");
    }

    @Test
    @DisplayName("价税合计缺失时回落 TotalAmount；横线日期同样解析")
    void fallsBackToTotalAmount() {
        JsonNode response = json("""
                {"words_result": {"TotalAmount": "88.00", "SellerName": "北京某酒店", "InvoiceDate": "2026-11-20"}}
                """);

        InvoiceFields fields = BaiduOcrClient.parseResponse(response);

        assertThat(fields.amount()).isEqualByComparingTo(new BigDecimal("88.00"));
        assertThat(fields.invoiceDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("金额缺失或非数字时返回 null（由服务层报「没有识别出金额」，不用 0 代替）")
    void keepsAmountNullWhenNotRecognizable() {
        JsonNode missing = json("{\"words_result\": {\"SellerName\": \"某公司\"}}");
        assertThat(BaiduOcrClient.parseResponse(missing).amount()).isNull();

        JsonNode garbage = json("{\"words_result\": {\"AmountInFiguers\": \"壹佰元整\"}}");
        assertThat(BaiduOcrClient.parseResponse(garbage).amount()).isNull();
    }

    @Test
    @DisplayName("error_code 非 0 抛 OcrException，消息带错误码但不含任何凭证")
    void errorCodeThrows() {
        JsonNode response = json("""
                {"error_code": 17, "error_msg": "Open api daily request limit reached"}
                """);

        assertThatThrownBy(() -> BaiduOcrClient.parseResponse(response))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("17")
                .hasMessageNotContaining("access_token")
                .hasMessageNotContaining("secret");
    }

    @Test
    @DisplayName("没有 words_result 或响应为空时抛 OcrException，而不是返回一张空发票")
    void missingWordsResultThrows() {
        assertThatThrownBy(() -> BaiduOcrClient.parseResponse(json("{\"log_id\": 1}")))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("没有返回字段");

        assertThatThrownBy(() -> BaiduOcrClient.parseResponse(null))
                .isInstanceOf(OcrException.class)
                .hasMessageContaining("没有返回内容");
    }

    @Test
    @DisplayName("非法日期当作未识别（不编日期）")
    void invalidDateIsNull() {
        assertThat(BaiduOcrClient.parseDate("2026年13月1日")).isNull();
        assertThat(BaiduOcrClient.parseDate("")).isNull();
    }
}