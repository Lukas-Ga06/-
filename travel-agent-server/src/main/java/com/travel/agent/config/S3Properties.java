package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** S3 兼容对象存储配置（照片多实例共享；AWS S3 / MinIO / 阿里 OSS / 腾讯 COS 均可） */
@Data
@Component
@ConfigurationProperties(prefix = "travel.photos.s3")
public class S3Properties {

    /** 对象存储 endpoint：AWS 用默认（留空），MinIO/OSS/COS 填各自 endpoint */
    private String endpoint = "";
    private String region = "us-east-1";
    private String bucket = "";
    private String accessKey = "";
    private String secretKey = "";

    /**
     * AWS SDK 最大重试次数；{@code null} = 沿用 SDK 默认（3 次重试）。
     *
     * <p>生产默认不改 SDK 语义；降级测试把它设为 0，让 5xx 一次性失败而不是重试三次拖长用例。
     * 需要「宁可快速失败让上层降级」的部署也可以显式设 0。
     */
    private Integer maxRetries;

    /**
     * 单次 S3 调用总超时（毫秒，含重试）；{@code null} = 沿用 SDK 默认（不设总超时，
     * 仅受 socket/OS 层约束）。测试用 200~600ms 断言「超时 → BusinessException(500) + timeout 降级指标」。
     */
    private Integer apiCallTimeoutMs;

    public boolean usable() {
        return bucket != null && !bucket.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
