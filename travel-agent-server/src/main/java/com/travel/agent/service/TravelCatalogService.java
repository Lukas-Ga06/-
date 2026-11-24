package com.travel.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import com.travel.agent.util.CityNames;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 旅行目录数据（景点/酒店/餐厅）。MVP 使用内置 JSON 目录，
 * 生产可替换为高德/美团等 POI 服务，工具接口不变。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TravelCatalogService {

    private final ObjectMapper objectMapper;
    private final RouteService routeService;

    /** 内置 + 联网生成的城市目录（并发读写安全） */
    private final List<CityCatalog> cities = new java.util.concurrent.CopyOnWriteArrayList<>();

    @PostConstruct
    public void load() {
        try {
            Map<String, List<CityCatalog>> data = objectMapper.readValue(
                    new ClassPathResource("data/travel-catalog.json").getInputStream(),
                    new TypeReference<>() {});
            cities.addAll(data.get("cities"));
        } catch (Exception e) {
            throw new IllegalStateException("加载旅行目录失败", e);
        }
    }

    /** 注册联网生成的城市目录（同名不重复），注册后所有搜索工具立即可用 */
    public void register(CityCatalog catalog) {
        // 联网目录是模型生成的：只接受「像城市名」的名字，避免把「成都吃」「州镇江的」
        // 这类从自然语言里裁出来的脏串注册成正式城市，污染 matchCity 与「本地已开通」列表。
        if (catalog == null || !CityNames.isPlausibleName(catalog.name())) {
            log.warn("拒绝注册可疑城市目录: {}", catalog == null ? null : catalog.name());
            return;
        }
        boolean exists = cities.stream().anyMatch(c -> c.name().equals(catalog.name()));
        if (!exists) {
            cities.add(catalog);
        }
    }

    public List<CityCatalog> cities() {
        return cities;
    }

    /**
     * 从自然语言中匹配城市。
     *
     * <p>先用共享规则取「最长命中」（不再按目录数组顺序 first-match）；完全没命中时，
     * 再用 {@link CityNames#nearest} 的收紧 typo 兜底（「杭洲」→杭州、「石家庒」→石家庄）。
     * 兜底只认「等长 + 仅 1 处不同 + 不同位置不在首位」：首字不同的「广州/苏州/常州」到「杭州」、
     * 「南京/东京」到「北京」都是目录外的真实城市，一律不认，继续走「不支持该目的地」/联网查询；
     * typo 兜底出现多解也返回 null，绝不静默换成另一个城市。
     */
    public CityCatalog matchCity(String text) {
        if (text == null || text.isBlank() || cities.isEmpty()) {
            return null;
        }
        // 先剥出发地从句：「北京出发去西安」应匹配西安，不能因为整句里先出现北京就选北京
        String destinationText = CityNames.stripOriginClause(text);
        List<String> names = cities.stream()
                .map(CityCatalog::name)
                .filter(CityNames::isPlausibleName)
                .toList();
        String matched = CityNames.longestMatch(destinationText, names);
        if (matched == null) {
            matched = nearestCityName(destinationText, names);
        }
        return matched == null ? null : cityByName(matched);
    }

    private CityCatalog cityByName(String name) {
        for (CityCatalog city : cities) {
            if (city.name().equals(name)) {
                return city;
            }
        }
        return null;
    }

    /**
     * typo 兜底：先对整段文本做一次收紧笔误匹配；再在文本上按「候选城市名长度」滑窗，
     * 这样「我想去杭洲」这类带前后缀的输入也能纠正成「杭州」。
     * 收紧边界与 {@link CityNames#nearest} 一致：等长、仅 1 处不同且不在首位（首位笔误不兜底），
     * 所以滑窗只用与候选名完全等长的窗口；多解返回 null，不猜。
     */
    private String nearestCityName(String text, List<String> names) {
        String query = CityNames.normalize(text);
        if (query.isEmpty()) {
            return null;
        }
        Set<String> hits = new LinkedHashSet<>();
        CityNames.nearest(query, names, 1).ifPresent(hits::add);

        Set<Integer> windowLengths = new TreeSet<>();
        for (String name : names) {
            windowLengths.add(Math.max(2, normalizedLength(name)));
        }
        int[] codePoints = query.codePoints().toArray();
        for (int window : windowLengths) {
            for (int start = 0; start + window <= codePoints.length; start++) {
                String fragment = new String(codePoints, start, window);
                CityNames.nearest(fragment, names, 1).ifPresent(hits::add);
            }
        }
        return hits.size() == 1 ? hits.iterator().next() : null;
    }

    private int normalizedLength(String name) {
        String normalized = CityNames.normalize(name);
        return normalized.codePointCount(0, normalized.length());
    }

    public CityCatalog requireCity(String text) {
        CityCatalog city = matchCity(text);
        if (city == null) {
            throw new BusinessException("暂不支持该目的地，当前已开通：" +
                    cities.stream().map(CityCatalog::name).collect(Collectors.joining("、")));
        }
        return city;
    }

    /** 搜索景点：venue 为 indoor/outdoor/null；near 为空则按评分排序 */
    public List<Attraction> searchAttractions(CityCatalog city, String venue, Double nearLat, Double nearLng, List<String> excludeNames, int limit) {
        List<Attraction> list = city.attractions().stream()
                .filter(a -> venue == null || a.venue().equals(venue))
                .filter(a -> excludeNames == null || excludeNames.stream().noneMatch(n -> n.equals(a.name())))
                .collect(Collectors.toList());
        if (nearLat != null && nearLng != null) {
            list.sort(Comparator.comparingDouble(a -> routeService.haversineKm(nearLat, nearLng, a.lat(), a.lng())));
        } else {
            list.sort(Comparator.comparingDouble(Attraction::rating).reversed());
        }
        return list.stream().limit(limit).toList();
    }

    public Attraction nearestAttraction(CityCatalog city, String venue, Double nearLat, Double nearLng, List<String> excludeNames) {
        List<Attraction> list = searchAttractions(city, venue, nearLat, nearLng, excludeNames, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Hotel> searchHotels(CityCatalog city, boolean preferSubway, BigDecimal maxPricePerNight, int limit) {
        List<Hotel> list = city.hotels().stream()
                .filter(h -> !preferSubway || h.nearSubway())
                .filter(h -> maxPricePerNight == null || BigDecimal.valueOf(h.pricePerNight()).compareTo(maxPricePerNight) <= 0)
                .sorted(Comparator.comparingDouble(Hotel::rating).reversed())
                .collect(Collectors.toList());
        if (list.isEmpty() && preferSubway) {
            // 放宽地铁偏好
            list = city.hotels().stream()
                    .filter(h -> maxPricePerNight == null || BigDecimal.valueOf(h.pricePerNight()).compareTo(maxPricePerNight) <= 0)
                    .sorted(Comparator.comparingDouble(Hotel::rating).reversed())
                    .collect(Collectors.toList());
        }
        if (list.isEmpty() && maxPricePerNight != null) {
            // 放宽价格
            list = city.hotels().stream()
                    .filter(h -> !preferSubway || h.nearSubway())
                    .sorted(Comparator.comparingDouble(Hotel::rating).reversed())
                    .collect(Collectors.toList());
        }
        return list.stream().limit(limit).toList();
    }

    public Hotel cheapestHotel(CityCatalog city) {
        return city.hotels().stream()
                .min(Comparator.comparingDouble(Hotel::pricePerNight))
                .orElse(null);
    }

    public List<Restaurant> searchRestaurants(CityCatalog city, Double nearLat, Double nearLng, int limit) {
        List<Restaurant> list = new java.util.ArrayList<>(city.restaurants());
        if (nearLat != null && nearLng != null) {
            list.sort(Comparator.comparingDouble(r -> routeService.haversineKm(nearLat, nearLng, r.lat(), r.lng())));
        } else {
            list.sort(Comparator.comparingDouble(Restaurant::rating).reversed());
        }
        return list.stream().limit(limit).toList();
    }
}
