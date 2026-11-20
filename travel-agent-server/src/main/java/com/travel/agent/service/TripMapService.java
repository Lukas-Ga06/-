package com.travel.agent.service;

import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.repository.TripRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 首页足迹地图：解析用户各行程目的地的坐标。
 * 三级降级：CityAtlas 图集（秒回）→ 内置旅行目录中心 → 联网快速定位（总限 8 秒）。
 *
 * 联网定位的并发策略（P1-6）：
 * - 不再复用 Agent/SSE 共用的 agentTaskExecutor：地图慢查询不应该拖慢对话流，反之亦然；
 *   这里用自己的 2 线程守护池，队列用无界 LinkedBlockingQueue（提交不会因为池小而抛满）。
 * - 结果收集进 ConcurrentLinkedQueue，超时后先 future.cancel(true) 再排序/返回，
 *   保证「读结果」发生在「不再有 worker 写入」之后（旧实现超时只 log，worker 仍在写 ArrayList）。
 * - 池已关闭/拒绝时降级为「只返回已定位的点」，不再抛 500。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripMapService {

    /** 联网定位总预算（毫秒）：超过就不再等，未完成的行程本次不显示标记 */
    private static final long LOCATE_BUDGET_MS = 8_000L;

    private final TripRepository tripRepository;
    private final CityAtlas cityAtlas;
    private final TravelCatalogService catalogService;
    private final OnlineCityService onlineCityService;
    /** P1：足迹区分 planned / visited / actualRoute（只认 visitedAt，不把计划当到访） */
    private final com.travel.agent.repository.ItineraryItemRepository itineraryItemRepository;

    /** 目的地坐标缓存（跨请求复用，避免重复联网） */
    private final Map<String, double[]> coordCache = new ConcurrentHashMap<>();

    /**
     * 地图专用小池：core=2、守护线程、命名 map-locate-。
     * 用无界队列而不是有界队列 + AbortPolicy：地图接口是「尽力而为」的读接口，
     * 多排一会儿比直接 500 好；总耗时仍由 LOCATE_BUDGET_MS 兜底。
     */
    private final ExecutorService locatePool = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "map-locate-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    @PreDestroy
    void shutdownLocatePool() {
        locatePool.shutdownNow();
    }

    public List<TripDtos.TripMapPoint> points(Long userId) {
        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(userId);

        // 结果先进并发队列：worker 与主线程都可能写/读，普通 ArrayList 会 CME 或丢元素
        Queue<TripDtos.TripMapPoint> located = new ConcurrentLinkedQueue<>();
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
                located.add(point(trip, c));
            } else {
                unknown.add(trip);
            }
        }

        // 图集未命中的城市：小池并行联网快速定位（总预算 8 秒，超时取消，失败则该行程不显示标记）
        List<Future<?>> futures = new ArrayList<>();
        for (Trip trip : unknown) {
            try {
                futures.add(locatePool.submit(() -> {
                    double[] c = coordCache.computeIfAbsent(
                            key(trip.getDestination()),
                            k -> onlineCityService.quickLocate(k));
                    located.add(point(trip, c));
                }));
            } catch (RejectedExecutionException e) {
                // 池已关闭/饱和：降级为已定位结果，绝不因为地图定位失败把整页打成 500
                log.warn("地图定位线程池拒绝任务，本次跳过联网定位: {}", trip.getDestination());
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCATE_BUDGET_MS);
        for (Future<?> future : futures) {
            long remaining = deadline - System.nanoTime();
            try {
                future.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // 超时先取消：避免 worker 在方法返回后继续往结果队列里写
                future.cancel(true);
                log.warn("足迹坐标联网定位超时，部分行程不显示标记");
            } catch (Exception e) {
                log.warn("足迹坐标联网定位失败，部分行程不显示标记: {}", e.toString());
            }
        }
        // 取消之后再排序、再拷贝成不可变列表：此时不可能再有并发写入
        List<TripDtos.TripMapPoint> points = new ArrayList<>(located);
        points.sort((a, b) -> {
            int ia = indexOf(trips, a.getTripId());
            int ib = indexOf(trips, b.getTripId());
            return Integer.compare(ia, ib);
        });
        return List.copyOf(points);
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
        List<com.travel.agent.entity.ItineraryItem> items =
                itineraryItemRepository.findByTripIdOrderByDayIndexAscStartTimeAsc(trip.getId());
        List<com.travel.agent.entity.ItineraryItem> visited = items.stream()
                .filter(item -> item.getVisitedAt() != null && item.getLatitude() != null && item.getLongitude() != null)
                .sorted(java.util.Comparator.comparing(com.travel.agent.entity.ItineraryItem::getVisitedAt)
                        .thenComparing(com.travel.agent.entity.ItineraryItem::getId,
                                java.util.Comparator.nullsLast(Long::compareTo)))
                .toList();
        int planned = (int) items.stream()
                .filter(item -> item.getLatitude() != null && item.getLongitude() != null)
                .count();
        List<TripDtos.RouteStop> route = visited.stream()
                .map(item -> TripDtos.RouteStop.builder()
                        .itemId(item.getId())
                        .title(item.getTitle())
                        .visitedAt(item.getVisitedAt().toString())
                        .lat(item.getLatitude())
                        .lng(item.getLongitude())
                        .build())
                .toList();
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
                .phase(trip.phase(java.time.LocalDate.now()))
                .plannedPlaces(planned)
                .visitedPlaces(visited.size())
                .visited(!visited.isEmpty())
                .actualRoute(route)
                .build();
    }
}