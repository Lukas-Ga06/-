package com.travel.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import com.travel.agent.model.RouteInfo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 旅行目录数据（景点/酒店/餐厅）。MVP 使用内置 JSON 目录，
 * 生产可替换为高德/美团等 POI 服务，工具接口不变。
 */
@Service
@RequiredArgsConstructor
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
        boolean exists = cities.stream().anyMatch(c -> c.name().equals(catalog.name()));
        if (!exists) {
            cities.add(catalog);
        }
    }

    public List<CityCatalog> cities() {
        return cities;
    }

    /** 从自然语言中匹配城市 */
    public CityCatalog matchCity(String text) {
        if (text != null) {
            for (CityCatalog city : cities) {
                if (text.contains(city.name())) {
                    return city;
                }
            }
        }
        return null;
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
