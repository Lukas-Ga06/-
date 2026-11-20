package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.config.AmapProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 城市坐标定位：高德地理编码优先，OSM Nominatim 兜底。
 *
 * <p>只做「给名字返回坐标」这一件事，不含城市目录生成与缓存；失败一律返回 {@code null}，
 * 由 {@link OnlineCityService} / {@link TripMapService} 等调用方决定降级方式。
 */
@Component
@Slf4j
public class CityGeoLocator {

    /** Nominatim 兜底：国内可达性一般，超时即放弃，不阻塞主流程 */
    private static final String NOMINATIM_SEARCH =
            "https://nominatim.openstreetmap.org/search?q=%s&format=json&limit=1&accept-language=zh-CN";

    private final AmapGeocodeClient amap;

    private volatile RestClient nominatimClient;

    public CityGeoLocator(AmapProperties amapProperties, MeterRegistry meterRegistry) {
        // AmapGeocodeClient 不是 Bean（只被本类持有）：baseUrl/超时预算与降级指标都从配置与注册表透传进去
        this.amap = new AmapGeocodeClient(amapProperties.getKey(), amapProperties.getBaseUrl(),
                amapProperties.getConnectTimeoutMs(), amapProperties.getReadTimeoutMs(), meterRegistry);
    }

    /** 是否配置了高德 Key（未配置时走 Nominatim / LLM 兜底路径） */
    public boolean amapUsable() {
        return amap.usable();
    }

    /**
     * 轻量定位（快速路径）：高德优先，失败或无 Key 时退回 Nominatim。不生成完整目录。
     *
     * @return [lat, lng]（WGS-84）；失败返回 null
     */
    public double[] locateCity(String name) {
        if (amap.usable()) {
            double[] located = amap.geocodeCity(name);
            if (located != null) {
                return located;
            }
        }
        return nominatimLocate(name);
    }

    /** 仅用高德定位（不做 Nominatim 兜底），供兜底路径并行获取城市中心 */
    public double[] locateCityViaAmap(String name) {
        return amap.usable() ? amap.geocodeCity(name) : null;
    }

    /** 批量定位 POI（高德 batch，逐批最多 10 个）；返回「POI 名 → 坐标」，未命中的不出现 */
    public Map<String, double[]> locatePois(List<String> names, String cityHint) {
        return amap.batchGeocode(names, cityHint);
    }

    private double[] nominatimLocate(String name) {
        try {
            JsonNode[] arr = nominatimClient().get()
                    .uri(URI.create(NOMINATIM_SEARCH.formatted(
                            URLEncoder.encode(name, StandardCharsets.UTF_8))))
                    .retrieve().body(JsonNode[].class);
            if (arr != null && arr.length > 0) {
                double lat = Double.parseDouble(arr[0].path("lat").asText("0"));
                double lng = Double.parseDouble(arr[0].path("lon").asText("0"));
                if (lat != 0 && lng != 0) {
                    return new double[]{lat, lng};
                }
            }
        } catch (Exception e) {
            log.info("Nominatim 定位不可达（已跳过）: {}", name);
        }
        return null;
    }

    private RestClient nominatimClient() {
        if (nominatimClient == null) {
            synchronized (this) {
                if (nominatimClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(3000);
                    factory.setReadTimeout(5000);
                    nominatimClient = RestClient.builder()
                            .requestFactory(factory)
                            .defaultHeader("User-Agent", "travel-agent-demo/0.1 (educational demo)")
                            .build();
                }
            }
        }
        return nominatimClient;
    }
}