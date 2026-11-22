package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.util.CityNames;
import com.travel.agent.util.CoordinateTransform;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 高德 Web 服务地理编码客户端（{@link CityGeoLocator} 的内部协作类，不注册为 Bean）。
 *
 * <p>职责边界：只负责「地址/城市名 → WGS-84 坐标」与高德返回体的解析，
 * 不含缓存、不含 LLM、不写目录。所有失败都返回 {@code null}/空 Map 并记日志，由调用方降级。
 *
 * <p>坐标系：高德返回 GCJ-02，出口统一转 WGS-84（见 {@link CoordinateTransform}）。
 */
@Slf4j
class AmapGeocodeClient {

    /** 单批地理编码上限（高德 batch 接口限制） */
    private static final int BATCH_LIMIT = 10;

    /** 默认根地址（配置缺失/空白时的兜底，与 {@code AmapProperties.baseUrl} 默认值一致） */
    static final String DEFAULT_BASE_URL = "https://restapi.amap.com";

    private final String amapKey;
    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final MeterRegistry meterRegistry;
    private volatile RestClient geoClient;

    /**
     * @param baseUrl          根地址（如 {@code https://restapi.amap.com}；降级测试指向本地 WireMock）
     * @param connectTimeoutMs 建连超时预算
     * @param readTimeoutMs    读超时预算（服务端固定延迟超过它即视为超时降级）
     * @param meterRegistry    {@code external.degraded{provider=amap}} 的写入目标，可为 null（不记指标）
     */
    AmapGeocodeClient(String amapKey, String baseUrl, int connectTimeoutMs, int readTimeoutMs,
                      MeterRegistry meterRegistry) {
        this.amapKey = amapKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl).replaceAll("/+$", "");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.meterRegistry = meterRegistry;
    }

    /** 是否配置了可用的高德 Key（空/空白一律视为未配置） */
    boolean usable() {
        return amapKey != null && !amapKey.isBlank();
    }

    /**
     * 按城市名定位，并校验返回的省/市/区县确实是该城市，避免「搜 A 城返回 B 城」被静默采用。
     *
     * @return [lat, lng]（WGS-84）；定位失败或结果与查询城市不符时返回 null
     */
    double[] geocodeCity(String city) {
        return geocode(city, null, true);
    }

    /** 按 POI 名定位（不做城市校验：POI 名本来就不等于城市名） */
    double[] geocode(String address, String cityHint) {
        return geocode(address, cityHint, false);
    }

    /**
     * 单点定位。
     *
     * @param verifyCity true 表示 address 本身就是城市名：校验 province/city/district 与它是否同一城市
     */
    private double[] geocode(String address, String cityHint, boolean verifyCity) {
        if (!usable()) {
            return null;
        }
        try {
            JsonNode resp = geoClient().get().uri(URI.create(amapGeoUri(
                    URLEncoder.encode(address, StandardCharsets.UTF_8), cityHint))).retrieve().body(JsonNode.class);
            if (resp == null) {
                ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_AMAP,
                        ExternalDegradationMetrics.REASON_BAD_RESPONSE);
                return null;
            }
            if (!"1".equals(resp.path("status").asText("1"))) {
                // HTTP 200 + status=0（如 INVALID_USER_KEY / QPS 超限 10021）：对端明确拒绝，按坏响应降级
                ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_AMAP,
                        ExternalDegradationMetrics.REASON_BAD_RESPONSE);
            }
            return firstLocation(resp, verifyCity ? address : null);
        } catch (Exception e) {
            ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_AMAP, e);
            log.info("高德定位失败: {} - {}", address, e.getMessage());
            return null;
        }
    }

    /**
     * 批量定位（每批最多 {@value #BATCH_LIMIT} 个，按传入顺序对齐返回）；数量对不齐或请求失败时退回逐个查询。
     */
    Map<String, double[]> batchGeocode(List<String> names, String city) {
        Map<String, double[]> result = new LinkedHashMap<>();
        if (!usable()) {
            return result;
        }
        for (int start = 0; start < names.size(); start += BATCH_LIMIT) {
            List<String> chunk = names.subList(start, Math.min(start + BATCH_LIMIT, names.size()));
            if (tryBatch(chunk, city, result)) {
                continue;
            }
            // 数量不对齐或请求失败：逐个补齐
            for (String n : chunk) {
                double[] loc = geocode(n, city);
                if (loc != null) {
                    result.put(n, loc);
                }
            }
        }
        return result;
    }

    /** 一批批量定位；成功（含响应数量对齐）返回 true */
    private boolean tryBatch(List<String> chunk, String city, Map<String, double[]> out) {
        String joined = chunk.stream()
                .map(n -> URLEncoder.encode(n, StandardCharsets.UTF_8))
                .collect(Collectors.joining("%7C"));
        try {
            JsonNode resp = geoClient().get().uri(URI.create(amapGeoUri(joined, city))).retrieve().body(JsonNode.class);
            JsonNode geocodes = resp == null ? null : resp.path("geocodes");
            if (resp == null || !"1".equals(resp.path("status").asText())
                    || !geocodes.isArray() || geocodes.size() != chunk.size()) {
                // 请求成功但内容不可用（数量对不齐 / status=0）：记一次坏响应，随后逐个查询兜底
                ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_AMAP,
                        ExternalDegradationMetrics.REASON_BAD_RESPONSE);
                return false;
            }
            for (int i = 0; i < geocodes.size(); i++) {
                double[] loc = CoordinateTransform.parseGcj02Location(geocodes.get(i).path("location").asText(""));
                if (loc != null) {
                    out.put(chunk.get(i), loc);
                }
            }
            return true;
        } catch (Exception e) {
            ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_AMAP, e);
            log.info("高德批量定位失败，退回逐个查询: {}", e.getMessage());
            return false;
        }
    }

    /** 拼完整 URI：根地址可配置（测试指向本地 WireMock），路径仍是高德 /v3/geocode/geo */
    private String amapGeoUri(String encodedAddress, String cityHint) {
        String uri = baseUrl + "/v3/geocode/geo?batch=true&address=" + encodedAddress
                + "&key=" + amapKey;
        if (cityHint != null && !cityHint.isBlank()) {
            uri += "&city=" + URLEncoder.encode(cityHint, StandardCharsets.UTF_8);
        }
        return uri;
    }

    /**
     * 取首个地理编码结果。
     *
     * @param expectedQuery 非空时校验结果：高德返回的省/市/区县名与查询名既不 {@link CityNames#matches}
     *                      也不在 {@link CityNames#nearest} 距离 1 范围内 → 视为定位失败（返回 null），
     *                      避免「搜 A 城返回 B 城却静默采用」。注意「杭州市」与「杭州」是同一城市，不算不符。
     */
    private double[] firstLocation(JsonNode resp, String expectedQuery) {
        JsonNode geocodes = resp == null ? null : resp.path("geocodes");
        if (geocodes != null && geocodes.isArray() && !geocodes.isEmpty()) {
            JsonNode first = geocodes.get(0);
            if (expectedQuery != null && !expectedQuery.isBlank() && !geoResultMatchesCity(expectedQuery, first)) {
                log.warn("在线地理编码结果与查询城市不符，按定位失败处理: 查询={} 返回 province={} city={} district={}",
                        expectedQuery,
                        textValue(first.path("province")), textValue(first.path("city")),
                        textValue(first.path("district")));
                return null;
            }
            return CoordinateTransform.parseGcj02Location(first.path("location").asText(""));
        }
        return null;
    }

    /**
     * 高德返回的省/市/区县任一与查询城市匹配，即视为同一城市。
     * 除 {@link CityNames#matches} 外只接受收紧笔误：等长、仅 1 处不同且不在首位
     * （「杭洲」→杭州 可接受；「广州」→杭州 这类首位笔误不认，宁可判不符，因为无法与真实异城区分）。
     */
    private boolean geoResultMatchesCity(String expected, JsonNode geocode) {
        List<String> returned = geoResultNames(geocode);
        if (returned.isEmpty()) {
            // 没有任何城市字段可比对：不能据此判定「不符」，不误杀
            return true;
        }
        for (String name : returned) {
            if (CityNames.matches(expected, name)) {
                return true;
            }
        }
        return CityNames.nearest(expected, returned, 1).isPresent();
    }

    private List<String> geoResultNames(JsonNode geocode) {
        List<String> names = new ArrayList<>();
        for (String field : List.of("province", "city", "district")) {
            String value = textValue(geocode.path(field));
            if (!value.isEmpty()) {
                names.add(value);
            }
        }
        return names;
    }

    /** 高德字段可能是字符串，也可能是空数组（直辖市/省直辖时 city 为 []），统一取首个非空文本 */
    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
            return "";
        }
        return node.asText("").trim();
    }

    private RestClient geoClient() {
        if (geoClient == null) {
            synchronized (this) {
                if (geoClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(connectTimeoutMs);
                    factory.setReadTimeout(readTimeoutMs);
                    geoClient = RestClient.builder()
                            .requestFactory(factory)
                            .defaultHeader("User-Agent", "travel-agent-demo/0.1 (educational demo)")
                            .build();
                }
            }
        }
        return geoClient;
    }
}