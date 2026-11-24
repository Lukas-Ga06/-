package com.travel.agent.service;

import com.travel.agent.config.S3Properties;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * S3 兼容对象存储（travel.photos.store=s3 时启用）：AWS S3 / MinIO / 阿里 OSS / 腾讯 COS。
 * 多实例部署共享同一 bucket，原图经 GetObject 流式返回；元数据仍在数据库（迁移时只搬对象）。
 */
@Component
@ConditionalOnProperty(prefix = "travel.photos", name = "store", havingValue = "s3")
@Slf4j
public class S3PhotoStore implements PhotoStore {

    private final S3Properties props;
    private final S3Client client;
    /** {@code external.degraded{provider=s3}} 的写入目标；null 时不记指标（既有单测的直接构造路径） */
    private final MeterRegistry meterRegistry;

    /** Spring 注入路径：按配置构建 SDK 客户端，并接收降级指标注册表 */
    @Autowired
    public S3PhotoStore(S3Properties props, MeterRegistry meterRegistry) {
        this(props, buildClient(props), meterRegistry);
    }

    /** 既有单测（Mockito 注入 S3Client）：不记指标，行为与历史完全一致 */
    S3PhotoStore(S3Properties props, S3Client client) {
        this(props, client, null);
    }

    S3PhotoStore(S3Properties props, S3Client client, MeterRegistry meterRegistry) {
        this.props = props;
        this.client = client;
        this.meterRegistry = meterRegistry;
        if (!props.usable()) {
            throw new IllegalStateException("travel.photos.store=s3 需要配置 travel.photos.s3.bucket/access-key/secret-key");
        }
        log.info("照片存储已切换为 S3 兼容对象存储 bucket={}", props.getBucket());
    }

    private static S3Client buildClient(S3Properties props) {
        // 默认保留 SDK 的尾校验（AWS/MinIO 正常返回校验头）；只有不返回校验头的兼容网关
        // 才通过 travel.photos.s3.checksum-validation=false 显式关闭，避免把成功上传误判为失败。
        var builder = S3Client.builder().region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .checksumValidationEnabled(props.isChecksumValidation())
                        .build());
        if (props.getEndpoint() != null && !props.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.getEndpoint()));
        }
        // 重试/超时默认沿用 SDK 语义（maxRetries/apiCallTimeoutMs 为 null 时不设），只有显式配置才覆盖
        ClientOverrideConfiguration.Builder overrides = ClientOverrideConfiguration.builder();
        if (props.getMaxRetries() != null) {
            overrides.retryPolicy(RetryPolicy.builder().numRetries(props.getMaxRetries()).build());
        }
        if (props.getApiCallTimeoutMs() != null) {
            overrides.apiCallTimeout(Duration.ofMillis(props.getApiCallTimeoutMs()));
        }
        return builder.forcePathStyle(props.isPathStyle())
                .overrideConfiguration(overrides.build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .build();
    }

    /** SDK 的 S3 5xx/4xx 带 HTTP 状态码，按 429/5xx/其它归类；其余（超时/连接/DNS）走通用分类 */
    private static String reasonOf(Throwable error) {
        if (error instanceof AwsServiceException serviceError) {
            return ExternalDegradationMetrics.reasonOfStatus(serviceError.statusCode());
        }
        return ExternalDegradationMetrics.reasonOf(error);
    }

    private void degraded(Throwable error) {
        ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_S3, reasonOf(error));
    }

    @Override
    public void store(MultipartFile file, String storedName) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(storedName)
                    .contentType(file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType())
                    .build();
            client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException | RuntimeException e) {
            degraded(e);
            log.error("照片上传对象存储失败: {}", storedName, e);
            throw new BusinessException(500, "照片保存失败，请重试");
        }
    }

    @Override
    public boolean exists(String storedName) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(props.getBucket()).key(storedName).build());
            return true;
        } catch (NoSuchKeyException e) {
            // 对象不存在是正常业务结果（404），不是降级
            return false;
        } catch (RuntimeException e) {
            // 保持既有语义：非 404 的 SDK 异常继续上抛，由 Controller 统一转 500；这里只补降级指标
            degraded(e);
            throw e;
        }
    }

    @Override
    public Resource read(String storedName) {
        try {
            var stream = client.getObject(GetObjectRequest.builder()
                    .bucket(props.getBucket()).key(storedName).build());
            return new InputStreamResource(stream);
        } catch (NoSuchKeyException e) {
            throw new BusinessException(404, "照片文件不存在（可能已被清理）");
        } catch (RuntimeException e) {
            degraded(e);
            log.error("照片读取失败: {}", storedName, e);
            throw new BusinessException(500, "照片读取失败，请重试");
        }
    }

    @Override
    public void deleteQuietly(String storedName) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(props.getBucket()).key(storedName).build());
        } catch (RuntimeException e) {
            degraded(e);
            log.warn("照片对象删除失败: {}", storedName, e);
        }
    }
}
