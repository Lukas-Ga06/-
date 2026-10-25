package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** 照片上传：同一用户在窗口内的最大请求数 */
    private int uploadLimit = 60;

    /** 其它接口：同一用户在窗口内的最大请求数 */
    private int defaultLimit = 600;

    /** 窗口长度（秒） */
    private long windowSeconds = 300;

    /** 计数存储：memory（默认，进程内）/ redis（多实例共享配额，需配置 spring.data.redis） */
    private String store = "memory";
}
