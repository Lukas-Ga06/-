package com.travel.agent.service;

import com.travel.agent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 照片文件存储（本地磁盘）。只负责"字节落盘"，不含任何权限与业务判断，
 * 因此 PhotoService 与 TripService 都可以依赖它而不会形成循环依赖。
 *
 * 目录可用 travel.photos.dir 覆盖，默认 data/photos。
 */
@Component
@Slf4j
public class PhotoStorage {

    private final String photoDir;

    public PhotoStorage(@Value("${travel.photos.dir:data/photos}") String photoDir) {
        this.photoDir = photoDir;
    }

    public void store(MultipartFile file, String storedName) {
        Path target = pathOf(storedName);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
        } catch (IOException e) {
            log.error("照片落盘失败: {}", target, e);
            throw new BusinessException(500, "照片保存失败，请重试");
        }
    }

    public Path pathOf(String storedName) {
        return Paths.get(photoDir).resolve(storedName).toAbsolutePath();
    }

    public boolean exists(String storedName) {
        return Files.exists(pathOf(storedName));
    }

    /** 删除磁盘文件；失败只记日志（元数据已处理，残留文件不影响功能） */
    public void deleteQuietly(String storedName) {
        try {
            Files.deleteIfExists(pathOf(storedName));
        } catch (IOException e) {
            log.warn("照片文件删除失败: {}", pathOf(storedName), e);
        }
    }
}
