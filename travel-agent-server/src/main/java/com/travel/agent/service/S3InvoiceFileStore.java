package com.travel.agent.service;

import com.travel.agent.config.S3Properties;
import com.travel.agent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;

/**
 * S3 兼容发票存储（travel.invoices.store=s3 时启用）。
 * 连接配置复用 travel.photos.s3.*（同集群共用一个对象存储），对象键前缀 invoices/ 与照片隔离。
 */
@Component
@ConditionalOnProperty(prefix = "travel.invoices", name = "store", havingValue = "s3")
@Slf4j
public class S3InvoiceFileStore implements InvoiceFileStore {

    private final S3Properties props;
    private final S3Client client;

    public S3InvoiceFileStore(S3Properties props) {
        this(props, buildClient(props));
    }

    S3InvoiceFileStore(S3Properties props, S3Client client) {
        this.props = props;
        this.client = client;
        if (!props.usable()) {
            throw new IllegalStateException("travel.invoices.store=s3 需要配置 travel.photos.s3.bucket/access-key/secret-key");
        }
        log.info("发票存储已切换为 S3 兼容对象存储 bucket={} prefix=invoices/", props.getBucket());
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
    public String store(byte[] bytes, String originalName) {
        String storedName = UUID.randomUUID().toString().replace("-", "")
                + LocalInvoiceFileStore.extensionOf(originalName);
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key("invoices/" + storedName)
                            .build(),
                    RequestBody.fromBytes(bytes));
            return storedName;
        } catch (RuntimeException e) {
            log.error("发票文件上传对象存储失败: {}", storedName, e);
            throw new BusinessException(500, "发票文件保存失败，请重试");
        }
    }
}
