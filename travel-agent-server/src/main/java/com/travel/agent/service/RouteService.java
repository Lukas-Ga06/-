package com.travel.agent.service;

import com.travel.agent.model.AmapRoute;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.util.ErrorSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 路线计算：真实路径规划优先，估算兜底（V2.2 FR-05）。
 *
 * <p>数据链路（与 AmapDataService 的既有约定一致）：
 * 高德 MCP 路径工具 → 高德 REST（配置 AMAP_KEY）→ 本地 haversine 估算。
 * 任一层失败都静默降级，绝不阻断规划主链路——无 Key / 离线环境下行为与升级前完全一致。
 *
 * <p>为什么需要缓存：一次三日游规划会按「相邻行程点」多次计算路线
 * （规则路径每天 3~5 段、结构化路径还要补交通腿），没有缓存时一次规划可能打出十几到几十次
 * 高德请求，既慢又容易触发配额。这里按坐标做进程内缓存：
 * <ul>
 *   <li>{@code amap}（真实路径）：{@value #CACHE_TTL_MS} 毫秒——真实道路数据不会变；</li>
 *   <li>{@code estimated}（降级估算）：{@link #ESTIMATED_CACHE_TTL_MS} 毫秒的短 TTL——
 *       降级多是**临时**故障（QPS 限流 10021、断网、超时），若和真实数据一样缓存 10 分钟，
 *       高德恢复后仍会被钉死成估算（验收报告 P6 实测）；短 TTL 既让恢复尽快生效，又不至于每次规划都重打外部接口。</li>
 * </ul>
 * 缓存条目上限 {@value #CACHE_MAX} 条，超出即整体清空——路线数据是幂等的，清空只影响命中率、不影响正确性。
 *
 * <p>口径说明：{@code distanceKm / walkMinutes / driveMinutes} 在真实路径可用时来自高德；
 * {@code taxiCost} 始终为本地估算（高德路径接口不返回价格，且没有接入计价 Provider）。
 * {@code source} 字段如实标注 distance 的来源（amap / estimated），供前端与 Trace 区分
 * 「真实数据」与「估算」，不允许把估算显示成实时数据。
 * {@code polyline} 只在 amap 结果上可能有值（WGS-84 [lat,lng]，已抽稀）；估算结果永远给空列表。
 *
 * <p>能力自报（V2.2 验收 P2）：{@link #provider()} 不再只看「配了 Key 没有」，而是
 * **配置 + 最近一次真实调用结果**共同决定——无效 Key / 最近一次调用失败时绝不报 amap。
 * 状态页另用 {@link #configured()} / {@link #verified()} / {@link #lastError()} 区分「已配置」与「已验证」。
 */
@Service
@Slf4j
public class RouteService {

    /** 真实路径（amap）结果缓存有效期：10 分钟（真实道路数据不会变，缓存避免同一次规划重复外呼） */
    static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /**
     * 估算（estimated）结果缓存有效期：60 秒。
     *
     * <p>为什么单独给一个短 TTL：估算结果代表「此刻没拿到真实数据」，
     * 高德 QPS 限流（10021）/ 短暂断网都是可自愈的；沿用 10 分钟会把一次限流失败钉死 10 分钟，
     * 用户看到的就全是估算（P6）。60 秒足够吸收同一次规划里的重复计算，又能让恢复后很快重新走真实路径。
     */
    static final long ESTIMATED_CACHE_TTL_MS = 60 * 1000L;
    /**
     * 缓存条目上限：500。
     *
     * <p>V2.2 起条目里带真实道路几何（≤200 点），单条目从「几十字节」涨到「数 KB」；
     * 2000 条的上限会把堆占用推到数十 MB，而路线缓存只是性能优化，因此下调到 500（约几 MB 上限）。
     */
    static final int CACHE_MAX = 500;

    /** distance 来源标记 */
    public static final String SOURCE_AMAP = "amap";
    public static final String SOURCE_ESTIMATED = "estimated";

    private final AmapDataService amapDataService;
    private final LongSupplier clock;
    private final Map<String, CachedRoute> cache = new ConcurrentHashMap<>();

    /** 无真实数据源（单测/纯估算场景）：保持既有构造语义 */
    public RouteService() {
        this(null);
    }

    @Autowired
    public RouteService(AmapDataService amapDataService) {
        this(amapDataService, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟，验证「估算短 TTL / 真实路径 10 分钟 TTL」而不必真的等待 */
    RouteService(AmapDataService amapDataService, LongSupplier clock) {
        this.amapDataService = amapDataService;
        this.clock = clock;
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
        long now = clock.getAsLong();
        CachedRoute cached = cache.get(key);
        if (cached != null && now - cached.at() < ttlFor(cached.info())) {
            return cached.info();
        }
        RouteInfo info = realRoute(fromLat, fromLng, toLat, toLng);
        if (info == null) {
            info = estimated(fromLat, fromLng, toLat, toLng);
        }
        putCache(key, info, now);
        return info;
    }

    /** 缓存 TTL 按来源区分：真实路径 10 分钟，估算结果只留 60 秒（见 {@link #ESTIMATED_CACHE_TTL_MS}） */
    private static long ttlFor(RouteInfo info) {
        return SOURCE_AMAP.equals(info.source()) ? CACHE_TTL_MS : ESTIMATED_CACHE_TTL_MS;
    }

    /**
     * 真实路径（驾车口径，含高德距离、时长与几何）；不可用返回 null。
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
            return new RouteInfo(km, walkMinutes, driveMinutes, taxiCost, SOURCE_AMAP, amap.polyline());
        } catch (RuntimeException e) {
            // P3：包装异常只打 message 会丢根因，这里记录「包装类型 -> 根因类型」
            log.warn("真实路径不可用，降级估算: {}", ErrorSummary.describe(e));
            return null;
        }
    }

    /** 估算口径（升级前的唯一实现，保留为降级路径；无几何，不给假点） */
    private RouteInfo estimated(double fromLat, double fromLng, double toLat, double toLng) {
        double km = haversineKm(fromLat, fromLng, toLat, toLng);
        int walkMinutes = (int) Math.round(km / 0.075);   // 步行 4.5 km/h
        int driveMinutes = (int) Math.round(km / 0.42 + 4); // 市区均速约 25 km/h
        double taxiCost = Math.round(12 + 2.4 * km);
        return new RouteInfo(Math.round(km * 10) / 10.0, walkMinutes, driveMinutes, taxiCost, SOURCE_ESTIMATED);
    }

    /**
     * 当前路线数据来源（供 {@code /api/system/status} 如实上报，不涉密钥）。
     *
     * <p>口径（V2.2 P2）：只有「配置了真实数据源」**且**「最近一次真实调用成功」才算 amap。
     * 无效 Key（10009/10001）、QPS 限流重试仍失败、启动后还没调用过，一律报 estimated——
     * 配置了不等于可用，估算必须自报家门（ADR-006）。
     */
    public String provider() {
        return configured() && verified() ? SOURCE_AMAP : SOURCE_ESTIMATED;
    }

    /** 是否配置了真实数据源（高德 Key 或高德 MCP 工具）；不代表 Key 有效 */
    public boolean configured() {
        return amapDataService != null && amapDataService.routeAvailable();
    }

    /** 最近一次真实路径调用是否成功（配置了但还没调用过时为 false，即「配置了但尚未验证」） */
    public boolean verified() {
        return amapDataService != null && amapDataService.routeVerified();
    }

    /** 最近一次真实路径调用的失败原因（含 infocode，不含 Key）；未失败/未调用时为 null */
    public String lastError() {
        return amapDataService == null ? null : amapDataService.routeLastError();
    }

    /** 坐标取 4 位小数（约 11 米精度）作为缓存键：足够区分不同地点，又能命中同点重复计算 */
    private static String cacheKey(double fromLat, double fromLng, double toLat, double toLng) {
        return String.format(Locale.ROOT, "%.4f,%.4f-%.4f,%.4f", fromLat, fromLng, toLat, toLng);
    }

    private void putCache(String key, RouteInfo info, long at) {
        if (cache.size() >= CACHE_MAX) {
            // 清空比逐条淘汰简单可靠：路线数据幂等，清空只降低命中率
            cache.clear();
        }
        cache.put(key, new CachedRoute(info, at));
    }
}
