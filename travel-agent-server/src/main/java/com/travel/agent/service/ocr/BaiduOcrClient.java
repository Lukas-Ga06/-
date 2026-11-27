package com.travel.agent.service.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.util.ExternalDegradationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度云增值税发票识别（{@code vat_invoice}）客户端。
 *
 * <p>两次调用：先用 client_credentials 换 access_token（缓存在内存，按 {@code expires_in} 提前 60 秒过期），
 * 再 POST 表单 {@code image=<base64>} 取 {@code words_result}。整个实现只负责协议与解析，
 * 业务判断（够不够、要不要确认）在 {@link OcrInvoiceService} 与 {@code InvoiceCommandHandler}。
 *
 * <p>失败口径：HTTP 异常、鉴权失败、{@code error_code} 非 0 都抛 {@link OcrException}，
 * 消息只带原因归类与对端错误码，<b>绝不带 Key / token / 带凭证的 URL</b>（R7）。
 * 金额缺失不算异常——返回 {@code amount=null}，由服务层明确报「没有识别出金额」。
 */
@Component
@Slf4j
public class BaiduOcrClient implements OcrClient {

    /** 发票日期可能是 2026年11月20日 / 2026-11-20 / 2026/11/20 / 2026.11.20，统一按数字抽取 */
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})\\D{1,3}(\\d{1,2})\\D{1,3}(\\d{1,2})");

    /** access_token 提前过期的秒数：避免「刚拿到就过期」的边界，换一次 token 比失败一次便宜 */
    private static final long TOKEN_EXPIRY_SKEW_SECONDS = 60;

    /** 对端错误信息与识别摘要的长度上限：它们会进用户可见文案与 Diff，不能无界 */
    private static final int MAX_MESSAGE_LENGTH = 120;
    private static final int MAX_RAW_TEXT_LENGTH = 1000;

    /**
     * 允许进入识别摘要的票面字段：省份/城市供同城匹配，发票类型只是票面种类。
     *
     * <p>白名单之外一律不进摘要——{@code Password}（密码区）、{@code CheckCode}（校验码）、
     * {@code PurchaserRegisterNum} / {@code SellerRegisterNum}（购销方纳税人识别号）等属于票据敏感信息。
     */
    private static final List<String> RAW_TEXT_FIELDS = List.of("Province", "City", "InvoiceType");

    private final OcrProperties properties;
    private final RestClient restClient;

    /** 内存缓存的 access_token；并发下可能各取一次，但结果等价（幂等），因此不加锁 */
    private volatile String accessToken;
    /** access_token 的失效时刻（epoch millis，已扣掉提前量） */
    private volatile long accessTokenExpiresAt;

    public BaiduOcrClient(OcrProperties properties) {
        this.properties = properties;
        // 与 WeatherService 同一写法：超时显式设置，不让默认值把对话线程挂住
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean available() {
        return properties.usable();
    }

    @Override
    public InvoiceFields recognize(byte[] imageBytes) {
        if (!available()) {
            throw new OcrException("发票 OCR 未配置");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new OcrException("发票图片为空");
        }
        String token = accessToken();
        OcrProperties.Baidu baidu = properties.getBaidu();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("image", Base64.getEncoder().encodeToString(imageBytes));
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(baidu.getEndpoint() + baidu.getInvoicePath() + "?access_token={token}", token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            // 只转述原因归类：异常 message 里可能带着含 token 的 URL
            String reason = ExternalDegradationMetrics.reasonOf(e);
            throw new OcrException("发票识别请求失败（" + reason + "）", reason);
        }
        return parseResponse(response);
    }

    /** 取 access_token：缓存有效就直接用，否则换一次并记录失效时刻 */
    private String accessToken() {
        String cached = accessToken;
        if (cached != null && System.currentTimeMillis() < accessTokenExpiresAt) {
            return cached;
        }
        OcrProperties.Baidu baidu = properties.getBaidu();
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(baidu.getEndpoint() + baidu.getTokenPath()
                                    + "?grant_type=client_credentials&client_id={id}&client_secret={secret}",
                            baidu.getApiKey(), baidu.getSecretKey())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            String reason = ExternalDegradationMetrics.reasonOf(e);
            throw new OcrException("发票 OCR 鉴权请求失败（" + reason + "）", reason);
        }
        String token = textOf(response, "access_token");
        if (token == null || token.isBlank()) {
            throw new OcrException("发票 OCR 鉴权失败（服务未返回 access_token）");
        }
        long expiresIn = response.path("expires_in").asLong(0);
        long ttlSeconds = Math.max(expiresIn - TOKEN_EXPIRY_SKEW_SECONDS, 1);
        accessTokenExpiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        accessToken = token;
        return token;
    }

    /**
     * {@code /rest/2.0/ocr/v1/vat_invoice} 的响应 → {@link InvoiceFields}（包内可见，便于纯单测）。
     *
     * <p>字段口径：金额取 {@code AmountInFiguers}（价税合计），缺失回落 {@code TotalAmount}；
     * 商户取 {@code SellerName}；日期取 {@code InvoiceDate}（多种分隔符都认）。
     * 缺失就是 {@code null}，不猜、不补默认值。
     */
    static InvoiceFields parseResponse(JsonNode response) {
        if (response == null || response.isNull() || response.isMissingNode()) {
            throw new OcrException("发票识别没有返回内容");
        }
        int errorCode = response.path("error_code").asInt(0);
        if (errorCode != 0) {
            String errorMsg = sanitize(textOf(response, "error_msg"), MAX_MESSAGE_LENGTH);
            throw new OcrException("发票识别失败（服务错误码 " + errorCode
                    + (errorMsg.isEmpty() ? "" : "：" + errorMsg) + "）");
        }
        JsonNode words = response.path("words_result");
        if (!words.isObject()) {
            throw new OcrException("发票识别没有返回字段（图片可能不是发票或不够清晰）");
        }
        BigDecimal amount = parseAmount(textOf(words, "AmountInFiguers"));
        if (amount == null) {
            amount = parseAmount(textOf(words, "TotalAmount"));
        }
        String merchant = blankToNull(textOf(words, "SellerName"));
        LocalDate invoiceDate = parseDate(textOf(words, "InvoiceDate"));
        String rawText = rawTextOf(words, merchant);
        return new InvoiceFields(amount, merchant, invoiceDate, null, rawText);
    }

    /** 金额文本 → BigDecimal：容忍 ¥ / ￥ / 元 / 千分位；解析不出返回 null（不抛异常） */
    static BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.replaceAll("[¥￥,，\\s]", "").replace("元", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 开票日期 → LocalDate；格式不认识或日期非法返回 null（记账日期由服务层回落当天） */
    static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException e) {
            // 非法日期（如 2026年13月1日）当作没识别出，不编一个日期出来
            return null;
        }
    }

    /**
     * 识别摘要：商品名 + 商户 + 省份/城市/发票类型（{@link #RAW_TEXT_FIELDS}）。
     * 两个下游用途：发票报销用它和商户一起做同城匹配；{@code InvoiceCommandHandler} 再过滤、截断后写进
     * Diff 与确认摘要，让用户在确认前核对「识别到了什么」，而不只看被规则归一后的三个字段。
     *
     * <p><b>不得遍历 {@code words} 全部字段</b>：票面密码区与校验码必须留在识别侧，
     * 一旦进入 rawText 就会随 Diff / SSE / 会话记录外泄（R7）。
     */
    private static String rawTextOf(JsonNode words, String merchant) {
        StringBuilder sb = new StringBuilder();
        String commodity = commodityText(words.path("CommodityName"));
        if (!commodity.isEmpty()) {
            sb.append(commodity).append(' ');
        }
        if (merchant != null) {
            sb.append(merchant).append(' ');
        }
        for (String field : RAW_TEXT_FIELDS) {
            String value = sanitize(textOf(words, field), MAX_MESSAGE_LENGTH);
            if (!value.isEmpty()) {
                sb.append(field).append('=').append(value).append(' ');
            }
        }
        return sanitize(sb.toString(), MAX_RAW_TEXT_LENGTH);
    }

    /** CommodityName 在不同端点下可能是数组（元素带 word）、对象或纯文本 */
    private static String commodityText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String word = item.isObject() ? sanitize(item.path("word").asText(""), MAX_MESSAGE_LENGTH)
                        : sanitize(item.asText(""), MAX_MESSAGE_LENGTH);
                if (!word.isEmpty()) {
                    sb.append(sb.length() == 0 ? "" : " ").append(word);
                }
            }
            return sb.toString();
        }
        return sanitize(node.asText(""), MAX_MESSAGE_LENGTH);
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** 对端文本 / 识别摘要进用户可见文案前：去掉控制字符（尤其 CR/LF，防伪造日志行与多行文案）并截断 */
    private static String sanitize(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() > maxLength ? oneLine.substring(0, maxLength) : oneLine;
    }
}