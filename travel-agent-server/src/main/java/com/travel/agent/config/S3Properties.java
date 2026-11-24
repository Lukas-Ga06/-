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
     * 是否强制 path-style（bucket 放在 URL 路径里，而不是 {@code bucket.endpoint} 子域名）。
     *
     * <p>MinIO 以及部分 S3 兼容网关必须用 path-style；AWS S3 默认 virtual-hosted-style。
     * 之前没有这个开关，MinIO 这类端点会因为 {@code bucket.127.0.0.1} 无法解析而失败。
     */
    private boolean pathStyle = false;

    /**
     * 是否校验对象存储返回的 MD5/CRC32 尾校验头（默认 true，与 SDK 默认一致）。
     *
     * <p>少数 S3 兼容网关不返回校验头，SDK 会把「上传成功」误判成校验失败；这类部署可显式关掉。
     * AWS S3 / 主流 MinIO 保持 true，不要为了绕过一个端点全局降级完整性校验。
     */
    private boolean checksumValidation = true;

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
