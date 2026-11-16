package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 限流配置。默认值面向单机小规模部署：注册/登录按 IP 限，Agent 与照片上传按用户限。
 * 多实例部署设 travel.rate-limit.store=redis，配额由全部实例共享（需配置 spring.data.redis）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.rate-limit")
public class RateLimitProperties {

    /** 总开关，测试环境关闭以免相互干扰 */
    private boolean enabled = true;

    /** 登录 / 注册：同一 IP 在窗口内的最大请求数 */
    private int authLimit = 10;

    /** Agent 对话（含 SSE）：同一用户在窗口内的最大请求数（直接对应模型花费） */
    private int agentLimit = 30;

    /**
     * 会话恢复专用（GET /api/auth/me）：同一用户在窗口内的最大请求数。
     *
     * <p>单独开桶而不是复用 authLimit（默认 10，面向登录爆破的 IP 桶）：
     * 前端每次刷新页面都要调 /me 恢复登录态，多标签页/多设备并发刷新时
     * 会把同一个 IP 的登录桶打满，导致"刚登录就被 429 踢出"。
     * 该桶阈值刻意放宽（默认 60），只用于防刷，不承担防爆破职责。
     */
    private int sessionLimit = 60;

    /** 照片上传：同一用户在窗口内的最大请求数 */
    private int uploadLimit = 60;

    /** 其它接口：同一用户在窗口内的最大请求数 */
    private int defaultLimit = 600;

    /** 窗口长度（秒） */
    private long windowSeconds = 300;

    /** 计数存储：memory（默认，进程内）/ redis（多实例共享配额，需配置 spring.data.redis） */
    private String store = "memory";

    /**
     * 受信代理（精确 IP 或 IPv4 CIDR，逗号分隔）。
     * 默认空 = 不信任任何代理：直接按 TCP 源地址计数，完全不读 X-Forwarded-For。
     * 只有后端"仅能经反代访问"时才能配置，否则照样可被伪造。
     */
    private List<String> trustedProxies = new ArrayList<>();

    /**
     * Spring 把空字符串绑定到 List 会得到 {@code [""]}，必须在这里过滤空白项，
     * 否则 {@code RATE_LIMIT_TRUSTED_PROXIES=}（默认值）会在列表里留下一个永远匹配不上的脏条目。
     */
    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>()
                : trustedProxies.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(rule -> !rule.isEmpty())
                        .toList();
    }
}
