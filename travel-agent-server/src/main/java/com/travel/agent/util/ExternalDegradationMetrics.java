package com.travel.agent.util;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.client.HttpStatusCodeException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 外部依赖降级的统一指标口：{@code external.degraded{provider,reason}}。
 *
 * <p>为什么集中在一处：高德 / LLM / Embedding / Redis / S3 的失败路径分散在各自的 catch 里，
 * 若每处自造标签，「超时」在 A 处叫 {@code timeout}、B 处叫 {@code io_timeout}，看板与告警就无法聚合。
 * 这里固定 provider（{@value #PROVIDER_AMAP} / {@value #PROVIDER_LLM} / {@value #PROVIDER_EMBEDDING}
 * / {@value #PROVIDER_REDIS} / {@value #PROVIDER_S3}）与 reason（{@value #REASON_TIMEOUT} /
 * {@value #REASON_RATE_LIMITED} / {@value #REASON_SERVER_ERROR} / {@value #REASON_BAD_RESPONSE} /
 * {@value #REASON_CONNECTION_ERROR}）两枚低基数标签。
 *
 * <p>铁律：<b>指标记录失败绝不能反噬业务</b>。外部依赖已经不健康的时候，
 * 绝不能因为 MeterRegistry 自身出问题把一次可降级的调用变成异常；因此 {@link #record} 吞掉一切运行时异常。
 * 只做降级计数，不记录耗时（耗时由各客户端既有的 {@code *.latency} 指标承担），避免与 T5 的指标重名。
 */
public final class ExternalDegradationMetrics {

    /** 指标名：外部依赖降级次数（每次「已降级」的失败调用 +1，不重复计数同一次调用） */
    public static final String METRIC = "external.degraded";

    public static final String PROVIDER_AMAP = "amap";
    public static final String PROVIDER_LLM = "llm";
    public static final String PROVIDER_EMBEDDING = "embedding";
    public static final String PROVIDER_REDIS = "redis";
    public static final String PROVIDER_S3 = "s3";

    /** 连接或读超时（含 Spring 的 ResourceAccessException 包装与 S3 的 ApiCallTimeoutException） */
    public static final String REASON_TIMEOUT = "timeout";
    /** 对端限流：HTTP 429（高德另用 infocode 10021 表达 QPS 超限，但 HTTP 层仍是 200，此处只管 429） */
    public static final String REASON_RATE_LIMITED = "rate_limited";
    /** 对端 5xx */
    public static final String REASON_SERVER_ERROR = "server_error";
    /** 响应可读但内容不可用：空体、坏 JSON、业务包体 status=0、字段缺失 */
    public static final String REASON_BAD_RESPONSE = "bad_response";
    /** 连不上：连接被拒、DNS 失败、网络不可达 */
    public static final String REASON_CONNECTION_ERROR = "connection_error";

    private ExternalDegradationMetrics() {
    }

    /** 记录一次降级；{@code registry} 为 null（纯单元测试/未装配指标）或计数失败时静默跳过 */
    public static void record(MeterRegistry registry, String provider, String reason) {
        if (registry == null) {
            return;
        }
        try {
            Counter.builder(METRIC)
                    .description("外部依赖降级次数（provider 与 reason 见类注释）")
                    .tag("provider", provider)
                    .tag("reason", reason)
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) {
            // 指标不得反噬业务：外部依赖已降级，不能再让监控组件决定业务成败
        }
    }

    /** 按异常根因归类后记录（provider 见本类常量） */
    public static void record(MeterRegistry registry, String provider, Throwable error) {
        record(registry, provider, reasonOf(error));
    }

    /** 把异常归类为 {@link #REASON_TIMEOUT} 等五种 reason 之一；无法识别一律按坏响应处理 */
    public static String reasonOf(Throwable error) {
        if (error == null) {
            return REASON_BAD_RESPONSE;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpStatusCodeException statusError) {
                return reasonOfStatus(statusError.getStatusCode().value());
            }
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (isTimeout(t)) {
                return REASON_TIMEOUT;
            }
            if (isConnectionFailure(t)) {
                return REASON_CONNECTION_ERROR;
            }
        }
        return REASON_BAD_RESPONSE;
    }

    /** HTTP 状态码 → reason：429 限流、5xx 服务端错误、其余 4xx/3xx 归为坏响应 */
    public static String reasonOfStatus(int status) {
        if (status == 429) {
            return REASON_RATE_LIMITED;
        }
        if (status >= 500) {
            return REASON_SERVER_ERROR;
        }
        return REASON_BAD_RESPONSE;
    }

    private static boolean isTimeout(Throwable t) {
        if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
            return true;
        }
        // Netty/S3/其它客户端用各自命名的超时异常（如 ApiCallTimeoutException），按类名兜底
        if (t.getClass().getSimpleName().contains("Timeout")) {
            return true;
        }
        String message = text(t);
        return message.contains("timed out") || message.contains("timeout");
    }

    private static boolean isConnectionFailure(Throwable t) {
        if (t instanceof ConnectException || t instanceof UnknownHostException
                || t instanceof NoRouteToHostException || t instanceof SocketException) {
            return true;
        }
        String message = text(t);
        return message.contains("connection refused") || message.contains("failed to connect")
                || message.contains("connection reset") || message.contains("unable to execute http request");
    }

    private static String text(Throwable t) {
        String message = t.getMessage();
        return message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
    }
}