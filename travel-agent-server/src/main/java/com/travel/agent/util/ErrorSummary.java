package com.travel.agent.util;

/**
 * 降级日志用的异常摘要：**记录根因类型**，并做密钥脱敏。
 *
 * <p>为什么需要它：验收报告 P3 指出，降级日志只打 {@code e.getMessage()} 时，
 * 包装异常（如 Spring 的 {@code ResourceAccessException}）会把「SocketTimeoutException / ConnectException」
 * 这类真正可排查的根因吞掉，日志里只剩一句 "I/O error"。
 * 本类把「包装类型 -> 根因类型: 根因消息」拼出来，排障时一眼能看出是超时还是连接被拒。
 *
 * <p>为什么还要脱敏：部分 HTTP 客户端异常的消息里会带完整请求 URL，而高德 REST 的 URL 带 {@code key=}。
 * 日志与 {@code /api/system/status} 都不得出现密钥，所以调用方把密钥传进来做字面替换。
 */
public final class ErrorSummary {

    /** 摘要最大长度：状态接口与日志都不需要完整堆栈/响应体 */
    private static final int MAX_LENGTH = 300;
    private static final String MASK = "***";

    private ErrorSummary() {
    }

    /**
     * 生成异常摘要，形如 {@code ResourceAccessException -> SocketTimeoutException: Read timed out}。
     *
     * @param error   异常（可为 null）
     * @param secrets 需要从文本中抹掉的敏感串（如 API Key）；null / 空白会被忽略
     */
    public static String describe(Throwable error, String... secrets) {
        if (error == null) {
            return "unknown";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        StringBuilder text = new StringBuilder();
        if (root != error) {
            text.append(error.getClass().getSimpleName()).append(" -> ");
        }
        text.append(root.getClass().getSimpleName());
        String message = root.getMessage();
        if (message != null && !message.isBlank()) {
            text.append(": ").append(message);
        }
        String summary = redact(text.toString(), secrets);
        return summary.length() <= MAX_LENGTH ? summary : summary.substring(0, MAX_LENGTH) + "…";
    }

    /** 把文本里出现的敏感串替换为 ***（空白密钥不参与替换，避免把空串插进每个字符之间） */
    public static String redact(String text, String... secrets) {
        if (text == null || text.isEmpty() || secrets == null) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                result = result.replace(secret, MASK);
            }
        }
        return result;
    }
}
