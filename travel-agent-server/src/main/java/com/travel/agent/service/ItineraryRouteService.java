package com.travel.agent.service;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.model.RouteInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程路线段（V2.2 FR-07：交互地图与每日行程的时间线↔地图联动数据）。
 *
 * <p>对每一天内「有坐标、按开始时间排序」的相邻条目计算一段路线：
 * 距离、步行/驾车时长、打车估算与<b>数据来源</b>（amap 真实路径 / estimated 估算）。
 * 前端据此在时间线里插入「转场」行、在地图上连线段；无坐标条目（酒店、交通费汇总）自动跳过，
 * 不产生假路线。
 *
 * <p>为什么单独成派：既有的 {@code GET /api/trips/{id}/itinerary} 响应形状被多处前端与测试依赖，
 * 直接加字段风险大；这里用独立只读端点返回补充数据，前端按需合并，旧调用方零影响。
 *
 * <p>性能：路线计算走 {@link RouteService}（带 10 分钟缓存）；一次三日游约 10~15 段，
 * 命中缓存时几乎零成本。无真实数据源时全部为估算口径，行为与升级前一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ItineraryRouteService {

    private final ItineraryService itineraryService;
    private final RouteService routeService;

    /** 每日路线段视图（FR-07）：返回按天分组的相邻段 */
    public List<ItineraryDtos.RouteLegDTO> forTrip(Long tripId) {
        List<ItineraryItem> items = itineraryService.listEntities(tripId);
        Map<Integer, List<ItineraryItem>> byDay = new LinkedHashMap<>();
        for (ItineraryItem item : items) {
            if (item.getLatitude() == null || item.getLongitude() == null) {
                continue; // 无坐标（如交通费汇总条目）不参与路线
            }
            int day = item.getDayIndex() == null ? 1 : item.getDayIndex();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(item);
        }

        List<ItineraryDtos.RouteLegDTO> legs = new ArrayList<>();
        List<Integer> days = new ArrayList<>(byDay.keySet());
        days.sort(Comparator.naturalOrder());
        for (Integer day : days) {
            List<ItineraryItem> dayItems = byDay.get(day).stream()
                    .sorted(Comparator.comparing(ItineraryItem::getStartTime,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            for (int i = 0; i + 1 < dayItems.size(); i++) {
                ItineraryItem from = dayItems.get(i);
                ItineraryItem to = dayItems.get(i + 1);
                RouteInfo route = routeService.route(from.getLatitude(), from.getLongitude(),
                        to.getLatitude(), to.getLongitude());
                legs.add(ItineraryDtos.RouteLegDTO.builder()
                        .dayIndex(day)
                        .sequence(i + 1)
                        .fromItemId(from.getId())
                        .toItemId(to.getId())
                        .fromTitle(from.getTitle())
                        .toTitle(to.getTitle())
                        .fromLatitude(from.getLatitude())
                        .fromLongitude(from.getLongitude())
                        .toLatitude(to.getLatitude())
                        .toLongitude(to.getLongitude())
                        .distanceKm(route.distanceKm())
                        .walkMinutes(route.walkMinutes())
                        .driveMinutes(route.driveMinutes())
                        .taxiCost(route.taxiCost())
                        .source(route.source())
                        .build());
            }
        }
        return legs;
    }
}