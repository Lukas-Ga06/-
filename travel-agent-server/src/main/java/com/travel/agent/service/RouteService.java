package com.travel.agent.service;

import com.travel.agent.model.AmapRoute;
import com.travel.agent.model.RouteInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路线计算：真实路径规划优先，估算兜底（V2.2 FR-05）。
 *
 * <p>数据链路（与 AmapDataService 的既有约定一致）：
 * 高德 MCP 路径工具 → 高德 REST（配置 AMAP_KEY）→ 本地 haversine 估算。
 * 任一层失败都静默降级，绝不阻断规划主链路——无 Key / 离线环境下行为与升级前完全一致。
 *
 * <p>为什么需要缓存：一次三日游规划会按「相邻行程点」多次计算路线
 * （规则路径每天 3~5 段、结构化路径还要补交通腿），没有缓存时一次规划可能打出十几到几十次
 * 高德请求，既慢又容易触发配额。这里按坐标做 10 分钟 TTL 的进程内缓存，
 * 命中率很高（同一天内多次往返同两点）。缓存条目上限 {@value #CACHE_MAX} 条，超出即整体清空——
 * 路线数据是幂等的，清空只影响命中率、不影响正确性。
 *
 * <p>口径说明：{@code distanceKm / walkMinutes / driveMinutes} 在真实路径可用时来自高德；
 * {@code taxiCost} 始终为本地估算（高德路径接口不返回价格，且没有接入计价 Provider）。
 * {@code source} 字段如实标注 distance 的来源（amap / estimated），供前端与 Trace 区分
 * 「真实数据」与「估算」，不允许把估算显示成实时数据。
 */
@Service
@Slf4j
public class RouteService {

    /** 缓存有效期：10 分钟（POI 距离不会变，缓存主要用于同一次规划内的重复计算） */
    static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 缓存条目上限 */
    static final int CACHE_MAX = 2000;

    /** distance 来源标记 */
    public static final String SOURCE_AMAP = "amap";
    public static final String SOURCE_ESTIMATED = "estimated";

    private final AmapDataService amapDataService;
    private final Map<String, CachedRoute> cache = new ConcurrentHashMap<>();

    /** 无真实数据源（单测/纯估算场景）：保持既有构造语义 */
    public RouteService() {
        this(null);
    }

    @Autowired
    public RouteService(AmapDataService amapDataService) {
        this.amapDataService = amapDataService;
    }

    private record CachedRoute(RouteInfo info, long at) {
    }

    public double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 两点间路线：优先真实路径规划，失败/未配置时用 haversine 估算。
     * 返回值不可变记录，调用方可安全共享。
     */
    public RouteInfo route(double fromLat, double fromLng, double toLat, double toLng) {
        String key = cacheKey(fromLat, fromLng, toLat, toLng);
        CachedRoute cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            return cached.info();
        }
        RouteInfo info = realRoute(fromLat, fromLng, toLat, toLng);
        if (info == null) {
            info = estimated(fromLat, fromLng, toLat, toLng);
        }
        putCache(key, info);
        return info;
    }

    /**
     * 真实路径（驾车口径，含高德距离与时长）；不可用返回 null。
     *
     * <p>为什么用驾车：行程规划的收入口径是「打车费用」，驾车距离/时长与打车一致；
     * 步行时长仍按距离估算（短距离步行速度稳定，且再打一次步行接口会翻倍外部调用）。
     */
    private RouteInfo realRoute(double fromLat, double fromLng, double toLat, double toLng) {
        if (amapDataService == null || !amapDataService.routeAvailable()) {
            return null;
        }
        try {
            AmapRoute amap = amapDataService.driving(fromLat, fromLng, toLat, toLng);
            if (amap == null || amap.distanceMeters() <= 0 || amap.durationSeconds() <= 0) {
                return null;
            }
            double km = Math.round(amap.distanceMeters() / 100.0) / 10.0;   // 保留 1 位小数
            int driveMinutes = (int) Math.max(1, Math.round(amap.durationSeconds() / 60.0));
            int walkMinutes = (int) Math.round(km / 0.075);                 // 步行 4.5 km/h
            double taxiCost = Math.round(12 + 2.4 * km);
            return new RouteInfo(km, walkMinutes, driveMinutes, taxiCost, SOURCE_AMAP);
        } catch (RuntimeException e) {
            log.info("真实路径不可用，降级估算: {}", e.getMessage());
            return null;
        }
    }

    /** 估算口径（升级前的唯一实现，保留为降级路径） */
    private RouteInfo estimated(double fromLat, double fromLng, double toLat, double toLng) {
        double km = haversineKm(fromLat, fromLng, toLat, toLng);
        int walkMinutes = (int) Math.round(km / 0.075);   // 步行 4.5 km/h
        int driveMinutes = (int) Math.round(km / 0.42 + 4); // 市区均速约 25 km/h
        double taxiCost = Math.round(12 + 2.4 * km);
        return new RouteInfo(Math.round(km * 10) / 10.0, walkMinutes, driveMinutes, taxiCost, SOURCE_ESTIMATED);
    }

    /** 当前路线数据来源（供 /api/system/status 如实上报，不涉密钥） */
    public String provider() {
        return amapDataService != null && amapDataService.routeAvailable() ? SOURCE_AMAP : SOURCE_ESTIMATED;
    }

    /** 坐标取 4 位小数（约 11 米精度）作为缓存键：足够区分不同地点，又能命中同点重复计算 */
    private static String cacheKey(double fromLat, double fromLng, double toLat, double toLng) {
        return String.format(Locale.ROOT, "%.4f,%.4f-%.4f,%.4f", fromLat, fromLng, toLat, toLng);
    }

    private void putCache(String key, RouteInfo info) {
        if (cache.size() >= CACHE_MAX) {
            // 清空比逐条淘汰简单可靠：路线数据幂等，清空只降低命中率
            cache.clear();
        }
        cache.put(key, new CachedRoute(info, System.currentTimeMillis()));
    }
}