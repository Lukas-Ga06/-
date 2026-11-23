package com.travel.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 天气查询结果（ADR-006 的进一步收紧）：<b>要么是可用预报，要么是「不可用 + 原因」</b>。
 *
 * <p>为什么不再返回一个「猜出来的 {@link WeatherInfo}」：模拟天气一旦被调用方当成事实，
 * 审核会拿假「小雨」判硬约束、规划会拿假降雨改用户行程，
 * 用户看到的是一个永远演得下去的演示，却分不清哪些结论来自真实预报。
 * 现在取不到真实天气就返回 {@code available=false}，由调用方决定「明确拒绝」还是「如实说没做校验」，
 * 而不是让数据层替调用方决定要不要撒谎。
 *
 * <p>JSON 形状（工具结果直接进模型上下文）：{@code available / info / reason / message}。
 * {@code reason} 是机器码（用于指标与排查），{@code message} 是给用户看的中文原因。
 * 模拟数据只在 {@code travel.weather.simulation=true}（测试/离线演示）下出现，
 * 此时 {@code available=true} 且 {@code info.source=simulated}，来源由 {@link #simulation()} 如实表达。
 */
public record WeatherLookup(WeatherInfo info, String reason) {

    /** 超出 Open-Meteo 免费预报窗口（今天起 16 天）：属于产品边界，不是外部依赖降级 */
    public static final String REASON_OUTSIDE_WINDOW = "outside-forecast-window";
    /** 内置目录与 Open-Meteo 地理编码都无法定位该城市：同上，属于输入边界 */
    public static final String REASON_CITY_NOT_FOUND = "city-not-found";
    /**
     * 以下五个 reason 与 {@code external.degraded{provider=weather,reason=...}} 的词表逐字一致：
     * 同一件事在指标、状态接口与用户文案里不需要二次翻译，也不会出现「超时」在 A 处叫 timeout、B 处叫 io_timeout。
     * （model 层不依赖 metrics/util，一致性由 WeatherServiceTest 的常量断言保证。）
     */
    public static final String REASON_CONNECTION_ERROR = "connection_error";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_RATE_LIMITED = "rate_limited";
    public static final String REASON_SERVER_ERROR = "server_error";
    public static final String REASON_BAD_RESPONSE = "bad_response";

    /** 可用预报（真实预报，或显式打开模拟开关后的确定性模拟数据） */
    public static WeatherLookup available(WeatherInfo info) {
        return new WeatherLookup(info, null);
    }

    /** 不可用 + 机器可读原因（见本类 REASON_* 常量） */
    public static WeatherLookup unavailable(String reason) {
        return new WeatherLookup(null, reason);
    }

    @JsonProperty("available")
    public boolean available() {
        return info != null;
    }

    /** 来源是不是模拟数据（只有测试/离线演示开关打开时才可能为 true） */
    @JsonIgnore
    public boolean simulation() {
        return info != null && info.isSimulated();
    }

    /** 兼容命名：失败原因（与 {@link #reason()} 同值） */
    @JsonIgnore
    public String failureReason() {
        return reason;
    }

    /** 用户可读中文原因；可用时为 null（没有需要解释的失败） */
    @JsonProperty("message")
    public String message() {
        if (available() || reason == null) {
            return null;
        }
        return switch (reason) {
            case REASON_OUTSIDE_WINDOW -> "超出 16 天预报窗口";
            case REASON_CITY_NOT_FOUND -> "城市无法定位";
            case REASON_CONNECTION_ERROR -> "网络不可达";
            case REASON_TIMEOUT -> "天气服务超时";
            case REASON_RATE_LIMITED -> "天气服务限流";
            case REASON_SERVER_ERROR -> "天气服务异常";
            default -> "响应不可用";
        };
    }
}