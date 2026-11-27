package com.travel.agent.service.ocr;

/**
 * 发票识别失败（网络、鉴权、服务端错误码、响应不可解析）。
 *
 * <p>消息会被拼进用户可见的回复，因此只能写「发生了什么」，<b>不得包含 API Key / Secret Key /
 * access_token / 完整请求 URL</b>（URL 里带凭证）；底层异常一律按原因归类后转述，不原样透传 message。
 *
 * <p>{@code reason} 是统一降级词表里的一项（{@code timeout} / {@code rate_limited} / {@code server_error} /
 * {@code bad_response} / {@code connection_error}），供降级指标使用；异常本身不携带任何凭证。
 */
public class OcrException extends RuntimeException {

    /** 统一降级词表里的原因；缺省按「响应不可用」计 */
    private final String reason;

    public OcrException(String message) {
        this(message, com.travel.agent.util.ExternalDegradationMetrics.REASON_BAD_RESPONSE);
    }

    public OcrException(String message, String reason) {
        super(message);
        this.reason = reason == null ? com.travel.agent.util.ExternalDegradationMetrics.REASON_BAD_RESPONSE : reason;
    }

    public String reason() {
        return reason;
    }
}