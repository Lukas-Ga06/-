package com.travel.agent.service;

import com.travel.agent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** 发票原始文件存储（本地磁盘，默认 data/invoices；travel.invoices.store=s3 切换对象存储） */
@Component
@ConditionalOnProperty(prefix = "travel.invoices", name = "store", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalInvoiceFileStore implements InvoiceFileStore {

    private final String invoiceDir;

    public LocalInvoiceFileStore(@Value("${travel.invoices.dir:data/invoices}") String invoiceDir) {
        this.invoiceDir = invoiceDir;
    }

    @Override
    public String store(byte[] bytes, String originalName) {
        String storedName = UUID.randomUUID().toString().replace("-", "") + extensionOf(originalName);
        Path target = Paths.get(invoiceDir).resolve(storedName).toAbsolutePath();
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return storedName;
        } catch (IOException e) {
            log.error("发票文件保存失败: {}", target, e);
            throw new BusinessException(500, "发票文件保存失败，请重试");
        }
    }

    static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : "";
    }
}
