package com.travel.agent.service;

/**
 * 发票原始文件存储抽象：只负责"字节存取"（当前业务只写不读——提取后仅留档）。
 * 默认本地磁盘；travel.invoices.store=s3 时切换 S3 兼容对象存储（连接配置复用
 * travel.photos.s3.*，对象键以 invoices/ 前缀与照片隔离）。
 */
public interface InvoiceFileStore {

    /** 保存文件并返回存储名（UUID + 原扩展名） */
    String store(byte[] bytes, String originalName);
}
