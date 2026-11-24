package com.travel.agent.service;

import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.util.CityNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 联网城市数据引擎：内置目录之外的城市，按需在线生成旅行目录。
 *
 * <p>本类只做编排与缓存决策，具体能力委托给三个协作类：
 * <ul>
 *   <li>{@link CityGeoLocator}：城市/POI 坐标（高德优先，Nominatim 兜底）；</li>
 *   <li>{@link OnlineCityCatalogReader}：提示词与模型 JSON → {@link CityCatalog}；</li>
 *   <li>{@link CityCatalogDiskCache}：跨进程的本地文件缓存。</li>
 * </ul>
 *
 * <p>快速路径（配置高德 Key 后启用）：
 *   1) 大模型精简生成 POI 名称与属性（紧凑 JSON，约 2~3 秒）；
 *   2) 高德地理编码 API（国内可达）批量解析真实坐标（约 1 秒）。
 * <p>兜底路径（无 Key）：大模型一次性生成含坐标的完整目录 + OSM Nominatim 并行定位。
 *
 * <p>两级缓存：内存 24 小时（本类）+ 本地文件 7 天（{@link CityCatalogDiskCache}）。
 */
@Service
@Slf4j
public class OnlineCityService {

    /** 内存缓存有效期（文件缓存 7 天，见 {@link CityCatalogDiskCache}） */
    private static final Duration TTL = Duration.ofHours(24);

    /** 这些词不是城市名，命中即视为「无效查询」，避免生成垃圾目录 */
    private static final Set<String> GENERIC_WORDS = Set.of("城市", "哪里", "那里", "这里", "地方");

    /** 判定「目录够用」的下限：景点 4 / 酒店 1 / 餐厅 2，不足则回落另一条路径 */
    private static final int MIN_ATTRACTIONS = 4;
    private static final int MIN_RESTAURANTS = 2;

    private final LlmClient llmClient;
    private final TravelCatalogService travelCatalogService;
    /** 阻塞式模型/HTTP 调用统一走 Agent 专用池，不占用 ForkJoin commonPool */
    private final ThreadPoolTaskExecutor agentTaskExecutor;
    private final CityGeoLocator geoLocator;
    private final OnlineCityCatalogReader catalogReader;
    private final CityCatalogDiskCache diskCache;

    private record CacheEntry(CityCatalog city, Instant createdAt) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public OnlineCityService(LlmClient llmClient,
                             TravelCatalogService travelCatalogService,
                             ThreadPoolTaskExecutor agentTaskExecutor,
                             CityGeoLocator geoLocator,
                             OnlineCityCatalogReader catalogReader,
                             CityCatalogDiskCache diskCache) {
        this.llmClient = llmClient;
        this.travelCatalogService = travelCatalogService;
        this.agentTaskExecutor = agentTaskExecutor;
        this.geoLocator = geoLocator;
        this.catalogReader = catalogReader;
        this.diskCache = diskCache;
    }

    /** 获取城市目录：内置目录未命中时联网生成（内存 24h + 本地文件 7 天） */
    public CityCatalog getOrLoad(String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty() || GENERIC_WORDS.contains(name) || !CityNames.isPlausibleName(name)) {
            // 「成都吃」「州镇江的」「发去厦门」这类自然语言裁剪残片绝不能进联网生成：
            // 模型会顺着名字编出一整套“城市目录”，再被注册成正式城市。
            log.info("拒绝无效城市名: {}", rawName);
            return null;
        }
        // 模糊命中缓存（"石家庄市" ↔ "石家庄"）；跳过历史遗留的脏城市名
        for (CacheEntry entry : cache.values()) {
            String known = entry.city().name();
            if (!CityNames.isPlausibleName(known)) {
                continue;
            }
            if (matchesCity(known, name)) {
                return entry.city();
            }
        }
        CacheEntry entry = cache.get(name);
        if (entry != null && entry.createdAt().plus(TTL).isAfter(Instant.now())) {
            return entry.city();
        }
        CityCatalog cached = diskCache.load(name);
        if (cached != null && CityNames.isPlausibleName(cached.name())) {
            log.info("命中本地城市缓存: {}", cached.name());
            // 文件缓存命中：回填内存与内置目录即可，不必再写一次盘
            travelCatalogService.register(cached);
            cache.put(name, new CacheEntry(cached, Instant.now()));
            return cached;
        }
        if (cached != null) {
            log.warn("丢弃本地缓存里的脏城市名: {}", cached.name());
        }
        CityCatalog city = geoLocator.amapUsable() ? loadViaAmap(name) : null;
        if (city == null) {
            city = loadViaLlmCoords(name);
        }
        return city;
    }

    /** 轻量定位：仅解析城市坐标（高德优先，Nominatim 兜底），不生成完整目录。失败返回 null。 */
    public double[] quickLocate(String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty() || GENERIC_WORDS.contains(name) || !CityNames.isPlausibleName(name)) {
            return null;
        }
        return geoLocator.locateCity(name);
    }

    // ==================== 快速路径：精简生成 + 高德批量定位 ====================

    private CityCatalog loadViaAmap(String name) {
        long start = System.currentTimeMillis();
        try {
            // 1. 高德定位城市本身（校验城市存在，且返回的确实是这个城市）
            double[] center = geoLocator.locateCityViaAmap(name);
            if (center == null) {
                log.warn("高德定位城市失败，退回兜底路径: {}", name);
                return null;
            }
            // 2. 大模型精简生成（紧凑 JSON，无坐标无描述）
            String raw = llmClient.chat(List.of(
                    LlmMessage.system("你只输出 JSON，不要任何解释、注释或代码块标记。"),
                    LlmMessage.user(catalogReader.compactPrompt(name))), 0.3);
            if (raw == null) {
                return null;
            }
            // 3. 高德批量解析所有 POI 坐标
            List<String> poiNames = catalogReader.compactPoiNames(raw);
            Map<String, double[]> coords = geoLocator.locatePois(poiNames, name);
            log.info("高德定位完成: {} 个 POI / {} 个成功，累计 {} ms",
                    poiNames.size(), coords.size(), System.currentTimeMillis() - start);

            // 4. 组装目录（定位失败的 POI 直接丢弃）
            CityCatalog catalog = catalogReader.readCompact(raw, name, coords);
            if (acceptIfUsable(name, catalog)) {
                log.info("快速路径完成: {} 共 {} ms", name, System.currentTimeMillis() - start);
                return catalog;
            }
            log.warn("快速路径数据不足: {} ({}景点/{}酒店/{}餐厅)，退回兜底路径",
                    name, catalog.attractions().size(), catalog.hotels().size(),
                    catalog.restaurants().size());
            return null;
        } catch (Exception e) {
            log.warn("高德快速路径失败: {} - {}", name, e.getMessage());
            return null;
        }
    }

    // ==================== 兜底路径：LLM 直接生成含坐标目录 ====================

    private CityCatalog loadViaLlmCoords(String name) {
        // 并行执行：联网定位（失败/超时不阻塞）+ 大模型生成目录（关键路径）
        CompletableFuture<double[]> geoFuture = CompletableFuture.supplyAsync(
                () -> geoLocator.locateCityViaAmap(name), agentTaskExecutor);
        CompletableFuture<String> llmFuture = CompletableFuture.supplyAsync(() -> {
            if (!llmClient.available()) {
                return null;
            }
            return llmClient.chat(List.of(
                    LlmMessage.system("你只输出 JSON，不要输出任何解释或其他内容。"),
                    LlmMessage.user(catalogReader.fullPrompt(name))), 0.4);
        }, agentTaskExecutor);

        String raw;
        try {
            raw = llmFuture.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("LLM 生成城市目录失败: {} - {}", name, e.getMessage());
            return null;
        }
        awaitGeo(geoFuture, name);
        if (raw == null) {
            return null;
        }
        try {
            CityCatalog catalog = catalogReader.readFull(raw, name);
            if (acceptIfUsable(name, catalog)) {
                return catalog;
            }
            log.warn("在线城市目录数据不足: {} ({}景点/{}酒店/{}餐厅)", name,
                    catalog.attractions().size(), catalog.hotels().size(), catalog.restaurants().size());
            return null;
        } catch (Exception e) {
            log.warn("在线生成城市目录失败: {} - {}", name, e.getMessage());
            return null;
        }
    }

    /** 等待兜底路径的定位结果；失败不影响目录生成（只影响后续「就近」排序的精度） */
    private void awaitGeo(CompletableFuture<double[]> geoFuture, String name) {
        try {
            double[] center = geoFuture.get(10, TimeUnit.SECONDS);
            if (center != null) {
                log.info("定位完成: {} -> ({}, {})", name, center[0], center[1]);
            }
        } catch (Exception ignored) {
            // 定位失败不影响生成结果
        }
    }

    /**
     * 目录可用性门槛：景点 ≥ {@value #MIN_ATTRACTIONS}、至少 1 家酒店、餐厅 ≥ {@value #MIN_RESTAURANTS}。
     * 达标则注册进内置目录 + 落盘并回填内存缓存，返回 true。
     */
    private boolean acceptIfUsable(String name, CityCatalog catalog) {
        if (catalog == null || !CityNames.isPlausibleName(catalog.name())) {
            log.warn("在线城市目录名字不可信，丢弃: {}", catalog == null ? null : catalog.name());
            return false;
        }
        if (catalog.attractions().size() < MIN_ATTRACTIONS
                || catalog.hotels().isEmpty()
                || catalog.restaurants().size() < MIN_RESTAURANTS) {
            return false;
        }
        // 以请求名作为规范名：目录内容是按 name 生成/定位的，模型回写的 name 可能是
        // 多城市拼接或别字；落库的必须是用户可再次检索到的确定城市名。
        CityCatalog canonical = new CityCatalog(name, catalog.attractions(), catalog.hotels(), catalog.restaurants());
        adopt(name, canonical);
        return true;
    }

    /** 目录进内存缓存 + 内置目录 + 本地文件缓存（三处保持一致） */
    private void adopt(String name, CityCatalog catalog) {
        travelCatalogService.register(catalog);
        cache.put(name, new CacheEntry(catalog, Instant.now()));
        diskCache.save(name, catalog);
    }

    /**
     * 城市名模糊匹配：完全相等，或一方包含另一方且较短一方长度 ≥ 2。
     *
     * <p>规则唯一实现在 {@link CityNames#matches}（本方法只保留包级入口给既有测试与调用方）。
     * 单字查询（「阳」「州」「北」）在任何情况下都不会命中城市，避免把用户带到无关城市。
     */
    static boolean matchesCity(String known, String query) {
        return CityNames.matches(known, query);
    }

    private String cleanName(String raw) {
        return CityNames.normalize(raw);
    }
}