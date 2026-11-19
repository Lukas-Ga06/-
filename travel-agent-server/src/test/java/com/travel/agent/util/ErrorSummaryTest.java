package com.travel.agent.util;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级日志的异常摘要（P3）：必须能看出根因类型（超时 / 连接拒绝），且不得泄露密钥。
 */
class ErrorSummaryTest {

    @Test
    void recordsRootCauseTypeNotOnlyWrapperMessage() {
        Exception wrapper = new RuntimeException("I/O error on GET request", new ConnectException("Connection refused"));
        String summary = ErrorSummary.describe(wrapper);
        assertThat(summary).contains("RuntimeException -> ConnectException").contains("Connection refused");
    }

    @Test
    void plainExceptionKeepsItsOwnType() {
        assertThat(ErrorSummary.describe(new TimeoutException("Read timed out")))
                .isEqualTo("TimeoutException: Read timed out");
    }

    @Test
    void apiKeyIsRedacted() {
        String secret = "k3y-in-url";
        Exception e = new RuntimeException("GET http://x?key=" + secret + " failed");
        String summary = ErrorSummary.describe(e, secret);
        assertThat(summary).doesNotContain(secret).contains("***");
        // 空密钥不能参与替换（否则会把空串插进每个字符之间）
        assertThat(ErrorSummary.describe(e, "")).contains(secret);
    }

    @Test
    void longMessageIsTruncated() {
        String summary = ErrorSummary.describe(new RuntimeException("x".repeat(1000)));
        assertThat(summary.length()).isLessThanOrEqualTo(301);
        assertThat(ErrorSummary.describe(null)).isEqualTo("unknown");
    }
}
