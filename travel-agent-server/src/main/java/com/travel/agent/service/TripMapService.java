package com.travel.agent.service;

import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 首页足迹地图：解析用户各行程目的地的坐标。
 * 三级降级：CityAtlas 图集（秒回）→ 内置旅行目录中心 → 联网快速定位（限 8 秒）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripMapService {

    private final TripRepository tripRepository;
    private final CityAtlas cityAtlas;
    private final TravelCatalogService catalogService;
    private final OnlineCityService onlineCityService;
    /** 联网定位也走 Agent 专用池，避免拖慢公共池上的其它任务 */
    private final ThreadPoolTaskExecutor agentTaskExecutor;

    /** 目的地坐标缓存（跨请求复用，避免重复联网） */
    private final Map<String, double[]> coordCache = new ConcurrentHashMap<>();

    public List<TripDtos.TripMapPoint> points(Long userId) {
        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(userId);

        List<TripDtos.TripMapPoint> points = new ArrayList<>();
        List<Trip> unknown = new ArrayList<>();
        for (Trip trip : trips) {
            // 已取消的行程没有真正发生，不计入足迹
            if (Trip.STATUS_CANCELLED.equals(trip.getStatus())) {
                continue;
            }
            double[] c = coordCache.get(key(trip.getDestination()));
            if (c == null) {
                c = cityAtlas.resolve(trip.getDestination());
            }
            if (c == null) {
                c = catalogCenter(trip.getDestination());
            }
            if (c != null) {
                coordCache.put(key(trip.getDestination()), c);
                points.add(point(trip, c));
            } else {
                unknown.add(trip);
            }
        }

        // 图集未命中的城市：并行联网快速定位（总限 8 秒，失败则该行程不显示标记）
        if (!unknown.isEmpty()) {
            CompletableFuture<?>[] futures = unknown.stream()
                    .map(trip -> CompletableFuture.runAsync(() -> {
                        double[] c = coordCache.computeIfAbsent(
                                key(trip.getDestination()),
                                k -> onlineCityService.quickLocate(k));
                        synchronized (points) {
                            points.add(point(trip, c));
                        }
                    }, agentTaskExecutor))
                    .toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(futures).get(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("足迹坐标联网定位超时，部分行程不显示标记: {}", e.getMessage());
            }
            points.sort((a, b) -> {
                int ia = indexOf(trips, a.getTripId());
                int ib = indexOf(trips, b.getTripId());
                return Integer.compare(ia, ib);
            });
        }
        return points;
    }

    private int indexOf(List<Trip> trips, Long tripId) {
        for (int i = 0; i < trips.size(); i++) {
            if (trips.get(i).getId().equals(tripId)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private String key(String destination) {
        return destination == null ? "" : destination.trim().replaceAll("(市|地区)$", "");
    }

    /** 内置旅行目录城市的中心（取其景点平均坐标） */
    private double[] catalogCenter(String destination) {
        CityCatalog city = catalogService.matchCity(destination);
        if (city == null || city.attractions().isEmpty()) {
            return null;
        }
        double lat = city.attractions().stream().mapToDouble(com.travel.agent.model.Attraction::lat).average().orElse(0);
        double lng = city.attractions().stream().mapToDouble(com.travel.agent.model.Attraction::lng).average().orElse(0);
        return lat == 0 ? null : new double[]{lat, lng};
    }

    private TripDtos.TripMapPoint point(Trip trip, double[] c) {
        return TripDtos.TripMapPoint.builder()
                .tripId(trip.getId())
                .name(trip.getName())
                .destination(trip.getDestination())
                .status(trip.getStatus())
                .startDate(trip.getStartDate() == null ? null : trip.getStartDate().toString())
                .endDate(trip.getEndDate() == null ? null : trip.getEndDate().toString())
                .budget(trip.getBudget())
                .lat(c == null ? null : c[0])
                .lng(c == null ? null : c[1])
                .build();
    }
}
