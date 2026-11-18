package com.travel.agent.model;

import java.time.LocalDate;

/**
 * 天气查询结果。
 *
 * <p>{@code source} 如实标注这条数据是**真实预报**还是**确定性模拟**（ADR-006 的延续）：
 * 接不到真实预报时（未开启 / 网络失败 / 超出预报窗口）自动降级为模拟数据，
 * 但审核侧必须能分辨——拿模拟的「小雨」去强制改用户行程等于把假数据当事实。
 */
public record WeatherInfo(
        String city,
        LocalDate date,
        String condition,
        int rainProbability,
        int tempLow,
        int tempHigh,
        String source
) {
    /** 真实预报（Open-Meteo） */
    public static final String SOURCE_REAL = "real";
    /** 确定性模拟数据（离线/失败降级） */
    public static final String SOURCE_SIMULATED = "simulated";

    /** 兼容旧调用：未指定来源时按模拟数据处理（模拟是默认口径，真实必须显式声明） */
    public WeatherInfo(String city, LocalDate date, String condition,
                       int rainProbability, int tempLow, int tempHigh) {
        this(city, date, condition, rainProbability, tempLow, tempHigh, SOURCE_SIMULATED);
    }

    public boolean isRainy() {
        return condition != null && condition.contains("雨");
    }

    /** 是否为模拟数据：模拟天气只能作提示，不能作为硬约束依据 */
    public boolean isSimulated() {
        return !SOURCE_REAL.equals(source);
    }
}
