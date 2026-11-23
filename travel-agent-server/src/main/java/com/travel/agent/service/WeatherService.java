package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.config.WeatherProperties;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.model.WeatherLookup;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 天气查询：默认（产品路径）只走 Open-Meteo 真实预报（免费、无需 Key）。
 *
 * <p><b>不再有「失败降级模拟」</b>：取不到真实天气就返回 {@link WeatherLookup#unavailable(String)} 与原因，
 * 由调用方决定「明确拒绝依赖天气的修改」还是「如实说本次没做雨天/户外校验」。
 * 这条约束是产品可被信任的前提——模拟天气一旦被当成事实，审核会拿假「小雨」判硬约束、
 * 规划会拿假降雨改用户行程，用户永远分不清哪条结论来自真实预报（ADR-006 的延续）。
 *
 * <p>模拟数据只在 {@code travel.weather.simulation=true}（仅测试/离线演示）下出现，
 * 且 {@link #provider()} 会自报 {@code simulated}，状态接口据此标注「模拟天气」。
 *
 * <p>两处缓存：成功的预报缓存 10 分钟（一次规划的多轮审核会重复查询）；失败的负缓存
 * {@code travel.weather.failure-cache-seconds}（默认 60 秒）——离线时一次三日游审核会查 3 次天气，
 * 没有负缓存就是每次规划都白等一轮连接超时。
 *
 * <p>指标：真实路径的依赖类失败计入 {@code external.degraded{provider=weather,reason=...}}，
 * reason 与 {@link WeatherLookup} 的常量同源（见 {@link ExternalDegradationMetrics}）。
 * 「超出 16 天窗口」「城市无法定位」属于输入边界而不是外部依赖降级，只进状态接口、不刷降级指标。
 */
@Service
@Slf4j
public class WeatherService {

    /** Open-Meteo 免费预报的窗口：今天起 16 天内 */
    static final int FORECAST_WINDOW_DAYS = 16;

    private static final List<String> CONDITIONS = List.of("晴", "多云", "阴", "多云", "晴", "阴", "小雨");

    /** 成功预报缓存有效期：同一城市同一天在一次规划的多轮审核里会重复查询（10 分钟足够覆盖） */
    static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 缓存条目上限：city+date 组合不会很多，超出即整体清空（数据幂等） */
    static final int CACHE_MAX = 512;

    private final WeatherProperties properties;
    private final TravelCatalogService catalogService;
    /** 可为 null（纯单测）：指标记录失败绝不影响业务 */
    private final MeterRegistry registry;
    private final RestClient restClient;

    /** 城市坐标缓存：内置目录算均值，目录外城市走 Open-Meteo 地理编码 */
    private final Map<String, double[]> coordCache = new ConcurrentHashMap<>();
    /** 预报结果缓存：规划 → 审核 → 重规划会按天重复查询 */
    private final Map<String, CachedWeather> forecastCache = new ConcurrentHashMap<>();
    /** 失败负缓存：离线时不要在每次审核里重复等同一个连接超时 */
    private final Map<String, CachedFailure> failureCache = new ConcurrentHashMap<>();
    /** 真实外呼次数（预报 + 地理编码），仅供测试断言「负缓存命中时不再外呼」 */
    private final AtomicInteger probes = new AtomicInteger();

    /** 最近一次真实失败的用户可读原因（不含任何密钥）；成功后清空 */
    private volatile String lastError;
    /** 最近一次真实调用是否成功（null 语义：进程启动后还没调用过 → false） */
    private volatile boolean lastRealCallOk;

    private record CachedWeather(WeatherInfo info, long at) {
    }

    private record CachedFailure(String reason, long at) {
    }

    /** 坐标解析结果：失败时带原因，绝不返回一个「猜出来的坐标」 */
    private record Coords(double lat, double lng, String failureReason, Throwable error) {
    }

    @Autowired
    public WeatherService(WeatherProperties properties, TravelCatalogService catalogService, MeterRegistry registry) {
        this.properties = properties;
        this.catalogService = catalogService;
        this.registry = registry;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "travel-agent-demo/0.1 (educational demo)")
                .build();
    }

    /** 无指标的便捷构造（纯单测与既有测试子类使用） */
    public WeatherService(WeatherProperties properties, TravelCatalogService catalogService) {
        this(properties, catalogService, null);
    }

    /**
     * 查某城市某天的天气。
     *
     * @return 可用预报（真实，或模拟开关打开时的确定性模拟）或「不可用 + 原因」；<b>任何情况下都不会用模拟填补真实失败</b>
     */
    public WeatherLookup forecast(String city, LocalDate date) {
        if (properties.isSimulation()) {
            return WeatherLookup.available(simulated(city, date));
        }
        if (city == null || city.isBlank() || date == null) {
            return unavailable(city, date, WeatherLookup.REASON_BAD_RESPONSE, null);
        }
        String key = cacheKey(city, date);
        CachedWeather cached = forecastCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            return WeatherLookup.available(cached.info());
        }
        CachedFailure failed = failureCache.get(key);
        if (failed != null && System.currentTimeMillis() - failed.at() < failureTtlMs()) {
            return WeatherLookup.unavailable(failed.reason());
        }
        WeatherLookup lookup = fetchReal(city, date);
        if (lookup.available()) {
            failureCache.remove(key);
            if (forecastCache.size() >= CACHE_MAX) {
                // 天气数据幂等：清空只影响命中率，不影响正确性
                forecastCache.clear();
            }
            forecastCache.put(key, new CachedWeather(lookup.info(), System.currentTimeMillis()));
        } else if (failureTtlMs() > 0) {
            if (failureCache.size() >= CACHE_MAX) {
                failureCache.clear();
            }
            failureCache.put(key, new CachedFailure(lookup.reason(), System.currentTimeMillis()));
        }
        return lookup;
    }

    // ==================== 运行态自报（供 /api/system/status） ====================

    /** 数据源口径：{@code open-meteo}（最近一次真实调用成功）/ {@code simulated}（模拟开关）/ {@code unavailable} */
    public String provider() {
        if (properties.isSimulation()) {
            return "simulated";
        }
        return lastRealCallOk ? "open-meteo" : "unavailable";
    }

    /** 最近一次真实失败的用户可读原因；没有失败过为 null（不含密钥） */
    public String lastError() {
        return lastError;
    }

    /** 最近一次真实调用是否成功（模拟路径不计入：它没有发生过真实调用） */
    public boolean verified() {
        return lastRealCallOk;
    }

    /** 真实外呼次数（预报 + 地理编码）：测试用来证明负缓存生效，不对外暴露 */
    int probeCount() {
        return probes.get();
    }

    // ==================== 真实路径（Open-Meteo） ====================

    private WeatherLookup fetchReal(String city, LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.isBefore(today) || date.isAfter(today.plusDays(FORECAST_WINDOW_DAYS))) {
            return unavailable(city, date, WeatherLookup.REASON_OUTSIDE_WINDOW, null);
        }
        Coords coords = resolveCoords(city);
        if (coords.failureReason() != null) {
            return unavailable(city, date, coords.failureReason(), coords.error());
        }
        try {
            probes.incrementAndGet();
            JsonNode resp = restClient.get()
                    .uri(properties.getForecastBaseUrl() + "?latitude={lat}&longitude={lng}"
                                    // timezone 这里不能写 %2F：RestClient 会对 URI 模板再做一次编码，
                                    // 变成 %252F 后 Open-Meteo 直接 400 Invalid timezone（真实预报永远不可用）
                                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                                    + "&timezone=Asia/Shanghai&start_date={date}&end_date={date}",
                            Map.of("lat", coords.lat(), "lng", coords.lng(), "date", date.toString()))
                    .retrieve()
                    .body(JsonNode.class);
            WeatherInfo info = parseForecast(city, date, resp);
            if (info == null) {
                return unavailable(city, date, WeatherLookup.REASON_BAD_RESPONSE, null);
            }
            lastRealCallOk = true;
            lastError = null;
            return WeatherLookup.available(info);
        } catch (RuntimeException e) {
            return unavailable(city, date, ExternalDegradationMetrics.reasonOf(e), e);
        }
    }

    /** 解析 Open-Meteo daily 结构；字段缺失/自相矛盾一律视为不可用（不猜一个天气出来） */
    private static WeatherInfo parseForecast(String city, LocalDate date, JsonNode resp) {
        JsonNode daily = resp == null ? null : resp.path("daily");
        int code = daily == null ? -1 : daily.path("weather_code").path(0).asInt(-1);
        if (code < 0) {
            return null;
        }
        String condition = describeWmoCode(code);
        if (condition == null) {
            return null;
        }
        int rainProbability = daily.path("precipitation_probability_max").path(0).asInt(0);
        double tempLow = daily.path("temperature_2m_min").path(0).asDouble();
        double tempHigh = daily.path("temperature_2m_max").path(0).asDouble();
        if (tempHigh < tempLow) {
            return null;
        }
        return new WeatherInfo(city, date, condition, rainProbability,
                (int) Math.round(tempLow), (int) Math.round(tempHigh), WeatherInfo.SOURCE_REAL);
    }

    /** 先用内置目录算城市中心（离线、稳定），目录外再走 Open-Meteo 地理编码 */
    private Coords resolveCoords(String city) {
        double[] cachedCoords = coordCache.get(city);
        if (cachedCoords != null) {
            return new Coords(cachedCoords[0], cachedCoords[1], null, null);
        }
        var catalog = catalogService == null ? null : catalogService.matchCity(city);
        if (catalog != null && catalog.attractions() != null && !catalog.attractions().isEmpty()) {
            double lat = catalog.attractions().stream().mapToDouble(a -> a.lat()).average().orElse(0);
            double lng = catalog.attractions().stream().mapToDouble(a -> a.lng()).average().orElse(0);
            if (lat != 0 && lng != 0) {
                coordCache.put(city, new double[]{lat, lng});
                return new Coords(lat, lng, null, null);
            }
        }
        try {
            probes.incrementAndGet();
            JsonNode resp = restClient.get()
                    .uri(properties.getGeocodeBaseUrl() + "?name={name}&count=1&language=zh&format=json",
                            Map.of("name", city))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode first = resp == null ? null : resp.path("results").path(0);
            double lat = first == null ? 0 : first.path("latitude").asDouble(0);
            double lng = first == null ? 0 : first.path("longitude").asDouble(0);
            if (lat == 0 || lng == 0) {
                // 服务可达但没有这个城市：输入边界，不是外部依赖降级
                return new Coords(0, 0, WeatherLookup.REASON_CITY_NOT_FOUND, null);
            }
            coordCache.put(city, new double[]{lat, lng});
            return new Coords(lat, lng, null, null);
        } catch (RuntimeException e) {
            return new Coords(0, 0, ExternalDegradationMetrics.reasonOf(e), e);
        }
    }

    /** WMO weather code → 中文天况；未知编码返回 null（视为不可用） */
    private static String describeWmoCode(int code) {
        if (code == 0) return "晴";
        if (code <= 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if (code <= 57) return "小雨";
        if (code == 61 || code == 80) return "小雨";
        if (code == 63 || code == 81) return "中雨";
        if (code == 65 || code == 82) return "大雨";
        if (code <= 67) return "中雨";
        if (code <= 77 || code == 85 || code == 86) return "雪";
        return "雷阵雨";
    }

    // ==================== 模拟路径（仅测试/离线演示开关） ====================

    private WeatherInfo simulated(String city, LocalDate date) {
        // floorMod 而非 Math.abs：String.hashCode() 可能等于 Integer.MIN_VALUE，abs 之后仍是负数，
        // 会让下面的取模得到负下标（ArrayIndexOutOfBoundsException）。对非负 hash，两者结果一致。
        int seed = Math.floorMod((String.valueOf(city) + date).hashCode(), Integer.MAX_VALUE);
        String condition = CONDITIONS.get(seed % CONDITIONS.size());
        int rainProbability = switch (condition) {
            case "小雨" -> 70 + seed % 20;
            case "阴" -> 20 + seed % 20;
            default -> seed % 15;
        };
        int tempLow = 14 + seed % 8;
        int tempHigh = tempLow + 7 + seed % 6;
        return new WeatherInfo(city, date, condition, rainProbability, tempLow, tempHigh);
    }

    // ==================== 失败口径 ====================

    /**
     * 统一失败出口：记运行态（lastError/verified）+ 降级指标，返回 unavailable。
     *
     * <p>指标只统计「外部依赖没给出可用结果」：有异常时按根因归类；没有异常（如 200 但字段缺失）
     * 按 {@code bad_response} 记一次。「超出窗口」「城市无法定位」不记指标——那不是依赖降级，
     * 记进去会让天气告警被用户输入的边界情况淹没。
     */
    private WeatherLookup unavailable(String city, LocalDate date, String reason, Throwable error) {
        lastRealCallOk = false;
        lastError = WeatherLookup.unavailable(reason).message();
        if (error != null) {
            ExternalDegradationMetrics.record(registry, ExternalDegradationMetrics.PROVIDER_WEATHER, error);
        } else if (!WeatherLookup.REASON_OUTSIDE_WINDOW.equals(reason)
                && !WeatherLookup.REASON_CITY_NOT_FOUND.equals(reason)) {
            ExternalDegradationMetrics.record(registry, ExternalDegradationMetrics.PROVIDER_WEATHER, reason);
        }
        log.warn("真实天气不可用: {} {} → {}{}", city, date, reason,
                error == null ? "" : "（" + error.getClass().getSimpleName() + "）");
        return WeatherLookup.unavailable(reason);
    }

    private static String cacheKey(String city, LocalDate date) {
        return city + "|" + date;
    }

    private long failureTtlMs() {
        return Math.max(0L, properties.getFailureCacheSeconds()) * 1000L;
    }
}