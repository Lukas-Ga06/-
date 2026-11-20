package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.config.WeatherProperties;
import com.travel.agent.model.WeatherInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天气查询：配置 WEATHER_REAL=true 时接 Open-Meteo 真实预报（免费、无需 Key），
 * 任何失败（网络异常、超出预报范围、解析不到）自动降级为确定性模拟数据。
 *
 * 模拟路径保留「用户提到雨就模拟降雨」的演示行为，便于离线演示「下雨换景点」；
 * 真实路径下是真实预报说了算——不下雨时「明天下雨换掉户外景点」会把户外景点换成另一个户外景点。
 */
@Service
@Slf4j
public class WeatherService {

    private static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    /** Open-Meteo 免费预报的窗口：今天起 16 天内 */
    private static final int FORECAST_WINDOW_DAYS = 16;

    private static final List<String> CONDITIONS = List.of("晴", "多云", "阴", "多云", "晴", "阴", "小雨");

    /** 预报缓存有效期：同一城市同一天在一次规划的多轮审核里会重复查询（10 分钟足够覆盖） */
    static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 缓存条目上限：city+date+mentionRain 组合不会很多，超出即整体清空（数据幂等） */
    static final int CACHE_MAX = 512;

    private final WeatherProperties properties;
    private final TravelCatalogService catalogService;
    private final RestClient restClient;
    /** 城市坐标缓存：内置目录算均值，目录外城市走 Open-Meteo 地理编码 */
    private final Map<String, double[]> coordCache = new ConcurrentHashMap<>();
    /** 预报结果缓存：规划 → 审核 → 重规划会按天重复查询 */
    private final Map<String, CachedWeather> forecastCache = new ConcurrentHashMap<>();

    private record CachedWeather(WeatherInfo info, long at) {
    }

    public WeatherService(WeatherProperties properties, TravelCatalogService catalogService) {
        this.properties = properties;
        this.catalogService = catalogService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "travel-agent-demo/0.1 (educational demo)")
                .build();
    }

    public WeatherInfo forecast(String city, LocalDate date, boolean mentionRain) {
        String key = city + "|" + date + "|" + mentionRain;
        CachedWeather cached = forecastCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            return cached.info();
        }
        WeatherInfo info = computeForecast(city, date, mentionRain);
        if (forecastCache.size() >= CACHE_MAX) {
            // 天气数据幂等：清空只影响命中率，不影响正确性
            forecastCache.clear();
        }
        forecastCache.put(key, new CachedWeather(info, System.currentTimeMillis()));
        return info;
    }

    /** 是否启用真实天气预报（false = 确定性模拟数据；单条结果的来源以 {@link WeatherInfo#isSimulated()} 为准） */
    private WeatherInfo computeForecast(String city, LocalDate date, boolean mentionRain) {
        if (properties.isReal()) {
            try {
                WeatherInfo real = fetchReal(city, date);
                if (real != null) {
                    return real;
                }
            } catch (Exception e) {
                log.warn("真实天气查询失败，降级为模拟数据: {} {} ({})", city, date, e.getMessage());
            }
        }
        return simulated(city, date, mentionRain);
    }

    // ==================== 真实路径（Open-Meteo） ====================

    private WeatherInfo fetchReal(String city, LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.isBefore(today) || date.isAfter(today.plusDays(FORECAST_WINDOW_DAYS))) {
            return null;
        }
        double[] coords = resolveCoords(city);
        if (coords == null) {
            return null;
        }
        JsonNode resp = restClient.get()
                .uri(FORECAST_URL + "?latitude={lat}&longitude={lng}"
                                // timezone 这里不能写 %2F：RestClient 会对 URI 模板再做一次编码，
                                // 变成 %252F 后 Open-Meteo 直接 400 Invalid timezone（真实预报永远降级成模拟）
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                                + "&timezone=Asia/Shanghai&start_date={date}&end_date={date}",
                        Map.of("lat", coords[0], "lng", coords[1], "date", date.toString()))
                .retrieve()
                .body(JsonNode.class);
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
    private double[] resolveCoords(String city) {
        return coordCache.computeIfAbsent(city, name -> {
            var catalog = catalogService.matchCity(name);
            if (catalog != null) {
                double lat = catalog.attractions().stream().mapToDouble(a -> a.lat()).average().orElse(0);
                double lng = catalog.attractions().stream().mapToDouble(a -> a.lng()).average().orElse(0);
                if (lat != 0 && lng != 0) {
                    return new double[]{lat, lng};
                }
            }
            return geocode(name);
        });
    }

    private double[] geocode(String city) {
        try {
            JsonNode resp = restClient.get()
                    .uri(GEO_URL + "?name={name}&count=1&language=zh&format=json", Map.of("name", city))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode first = resp == null ? null : resp.path("results").path(0);
            double lat = first == null ? 0 : first.path("latitude").asDouble(0);
            double lng = first == null ? 0 : first.path("longitude").asDouble(0);
            return (lat != 0 && lng != 0) ? new double[]{lat, lng} : null;
        } catch (Exception e) {
            log.info("城市地理编码失败: {} ({})", city, e.getMessage());
            return null;
        }
    }

    /** WMO weather code → 中文天况；未知编码返回 null（触发降级） */
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

    // ==================== 模拟路径（MVP 演示数据） ====================

    private WeatherInfo simulated(String city, LocalDate date, boolean mentionRain) {
        // floorMod 而非 Math.abs：String.hashCode() 可能等于 Integer.MIN_VALUE，abs 之后仍是负数，
        // 会让下面的取模得到负下标（ArrayIndexOutOfBoundsException）。对非负 hash，两者结果一致。
        int seed = Math.floorMod((city + date).hashCode(), Integer.MAX_VALUE);
        String condition = CONDITIONS.get(seed % CONDITIONS.size());
        int rainProbability = switch (condition) {
            case "小雨" -> 70 + seed % 20;
            case "阴" -> 20 + seed % 20;
            default -> seed % 15;
        };
        if (mentionRain) {
            condition = "小雨";
            rainProbability = 80;
        }
        int tempLow = 14 + seed % 8;
        int tempHigh = tempLow + 7 + seed % 6;
        return new WeatherInfo(city, date, condition, rainProbability, tempLow, tempHigh);
    }
}
