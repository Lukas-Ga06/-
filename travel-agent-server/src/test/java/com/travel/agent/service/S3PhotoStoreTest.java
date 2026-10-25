package com.travel.agent.service;

import com.travel.agent.config.S3Properties;
import com.travel.agent.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S3 兼容照片存储：put/get/head/delete 调用映射与异常语义（NoSuchKey → 不存在/404） */
class S3PhotoStoreTest {

    private final S3Client client = mock(S3Client.class);
    private final S3PhotoStore store = new S3PhotoStore(props(), client);

    private S3Properties props() {
        S3Properties props = new S3Properties();
        props.setBucket("travel-photos");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        props.setEndpoint("http://127.0.0.1:9000");
        return props;
    }

    @Test
    void storePutsObject() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "p.jpg", "image/jpeg", new byte[]{1, 2, 3});
        store.store(file, "u1/abc.jpg");
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void existsUsesHeadAndMapsNoSuchKey() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        assertTrue(store.exists("u1/abc.jpg"));
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertFalse(store.exists("u1/gone.jpg"));
    }

    @Test
    void readReturnsResourceAndMaps404() {
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(new byte[]{9, 9})));
        var resource = store.read("u1/abc.jpg");
        assertTrue(resource instanceof org.springframework.core.io.InputStreamResource);

        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        var ex = assertThrows(BusinessException.class, () -> store.read("u1/gone.jpg"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void deleteQuietlySwallowsErrors() {
        store.deleteQuietly("u1/abc.jpg");
        verify(client).deleteObject(any(DeleteObjectRequest.class));
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("network down"));
        store.deleteQuietly("u1/abc.jpg");
    }
}
