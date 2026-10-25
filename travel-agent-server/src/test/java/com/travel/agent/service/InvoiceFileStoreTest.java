package com.travel.agent.service;

import com.travel.agent.config.S3Properties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

/** 发票存储：本地 UUID 命名/扩展名白名单；S3 实现按 invoices/ 前缀上传 */
class InvoiceFileStoreTest {

    @Test
    void localStoreUsesUuidAndSanitizedExtension() {
        LocalInvoiceFileStore store = new LocalInvoiceFileStore(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "inv-test").toString());
        String name = store.store(new byte[]{1, 2}, "发票.PDF");
        assertTrue(name.matches("[0-9a-f]{32}\\.pdf"), "UUID + 小写扩展名: " + name);

        assertEquals(".pdf", LocalInvoiceFileStore.extensionOf("a.PDF"));
        assertEquals("", LocalInvoiceFileStore.extensionOf("noext"));
        assertEquals("", LocalInvoiceFileStore.extensionOf("backup.php~"), "非字母数字后缀被拒");
        assertEquals(".gz", LocalInvoiceFileStore.extensionOf("weird.tar.gz"), "取最后一个扩展名");
        assertEquals("", LocalInvoiceFileStore.extensionOf(null));
    }

    @Test
    void s3StoreUploadsWithInvoicesPrefix() {
        S3Client client = mock(S3Client.class);
        S3Properties props = new S3Properties();
        props.setBucket("b");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        S3InvoiceFileStore store = new S3InvoiceFileStore(props, client);
        String name = store.store(new byte[]{9}, "invoice.pdf");
        assertTrue(name.endsWith(".pdf"));
        verify(client).putObject(argThat((PutObjectRequest r) ->
                        "b".equals(r.bucket()) && r.key().startsWith("invoices/")),
                any(RequestBody.class));
    }

    @Test
    void s3FailureMapsToBusinessError() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("network down"));
        S3Properties props = new S3Properties();
        props.setBucket("b");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        S3InvoiceFileStore store = new S3InvoiceFileStore(props, client);
        var ex = assertThrows(com.travel.agent.exception.BusinessException.class,
                () -> store.store(new byte[]{1}, "x.pdf"));
        assertEquals(500, ex.getCode());
    }
}
