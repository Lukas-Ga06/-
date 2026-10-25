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

    public boolean usable() {
        return bucket != null && !bucket.isBlank()
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
