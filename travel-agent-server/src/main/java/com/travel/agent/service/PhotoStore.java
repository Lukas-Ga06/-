package com.travel.agent.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * 照片字节存储抽象：只负责"字节存取"，不含任何权限与业务判断，
 * 因此 PhotoService 与 TripService 都可以依赖它而不会形成循环依赖。
 *
 * 默认本地磁盘（LocalPhotoStore）；travel.photos.store=s3 时切换为 S3 兼容对象存储
 * （S3PhotoStore，兼容 MinIO / 阿里 OSS / 腾讯 COS），多实例共享同一 bucket。
 */
public interface PhotoStore {

    void store(MultipartFile file, String storedName);

    boolean exists(String storedName);

    /** 原图读取：本地实现返回文件资源，S3 实现返回对象流资源 */
    Resource read(String storedName);

    /** 删除文件；失败只记日志（元数据已处理，残留对象不影响功能） */
    void deleteQuietly(String storedName);
}
