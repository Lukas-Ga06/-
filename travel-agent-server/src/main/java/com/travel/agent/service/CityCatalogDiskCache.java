package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.model.CityCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 联网城市目录的本地文件缓存（{@code data/online-cities/<城市>.json}，7 天有效期）。
 *
 * <p>与内存缓存的分工：内存缓存是 24h 热数据、按城市名精确命中；
 * 本类负责跨进程重启保留，读到的目录由 {@link OnlineCityService} 决定是否回填内存与注册。
 *
 * <p>读写失败一律降级为「未命中」，不抛出异常：缓存坏了不能挡住在线生成。
 */
@Component
@Slf4j
public class CityCatalogDiskCache {

    /** 文件缓存有效期（内存缓存 24h 更短，见 {@link OnlineCityService}） */
    private static final Duration FILE_TTL = Duration.ofDays(7);

    private final ObjectMapper objectMapper;
    private final Path cacheDir = Paths.get("data", "online-cities");

    public CityCatalogDiskCache(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取城市目录缓存。
     *
     * @return 未命中、已过期或内容不完整时返回 null
     */
    public CityCatalog load(String name) {
        try {
            Path file = fileOf(name);
            if (!Files.exists(file)) {
                return null;
            }
            JsonNode wrapper = objectMapper.readTree(file.toFile());
            Instant savedAt = Instant.parse(wrapper.path("savedAt").asText(Instant.EPOCH.toString()));
            if (savedAt.plus(FILE_TTL).isBefore(Instant.now())) {
                return null;
            }
            CityCatalog catalog = objectMapper.treeToValue(wrapper.path("city"), CityCatalog.class);
            if (catalog != null && !catalog.attractions().isEmpty()) {
                return catalog;
            }
        } catch (Exception e) {
            log.warn("读取本地城市缓存失败: {} - {}", name, e.getMessage());
        }
        return null;
    }

    /** 写入城市目录缓存；失败只记日志（在线生成的目录本轮仍然可用） */
    public void save(String name, CityCatalog catalog) {
        try {
            Files.createDirectories(cacheDir);
            Map<String, Object> wrapper = Map.of("savedAt", Instant.now().toString(), "city", catalog);
            objectMapper.writeValue(fileOf(name).toFile(), wrapper);
        } catch (Exception e) {
            log.warn("写入本地城市缓存失败: {} - {}", name, e.getMessage());
        }
    }

    private Path fileOf(String name) {
        return cacheDir.resolve(sanitize(name) + ".json");
    }

    /** 城市名做文件名净化（中文不受影响，去掉路径分隔符与控制字符） */
    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "");
    }
}