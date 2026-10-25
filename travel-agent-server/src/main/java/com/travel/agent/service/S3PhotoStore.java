package com.travel.agent.service;

import com.travel.agent.config.S3Properties;
import com.travel.agent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;

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

    public S3PhotoStore(S3Properties props) {
        this(props, buildClient(props));
    }

    S3PhotoStore(S3Properties props, S3Client client) {
        this.props = props;
        this.client = client;
        if (!props.usable()) {
            throw new IllegalStateException("travel.photos.store=s3 需要配置 travel.photos.s3.bucket/access-key/secret-key");
        }
        log.info("照片存储已切换为 S3 兼容对象存储 bucket={}", props.getBucket());
    }

    private static S3Client buildClient(S3Properties props) {
        var builder = S3Client.builder().region(Region.of(props.getRegion()));
        if (props.getEndpoint() != null && !props.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.getEndpoint()));
        }
        return builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .build();
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
            return false;
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
            log.error("照片读取失败: {}", storedName, e);
            throw new BusinessException(500, "照片读取失败，请重试");
        }
    }

    @Override
    public void deleteQuietly(String storedName) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(props.getBucket()).key(storedName).build());
        } catch (RuntimeException e) {
            log.warn("照片对象删除失败: {}", storedName, e);
        }
    }
}
