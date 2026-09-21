package com.travel.agent.service;

import com.travel.agent.model.RouteInfo;
import org.springframework.stereotype.Service;

/** 路线计算（MVP 为基于经纬度的估算，可替换为高德/百度路径规划 API） */
@Service
public class RouteService {

    public double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public RouteInfo route(double fromLat, double fromLng, double toLat, double toLng) {
        double km = haversineKm(fromLat, fromLng, toLat, toLng);
        int walkMinutes = (int) Math.round(km / 0.075);   // 步行 4.5 km/h
        int driveMinutes = (int) Math.round(km / 0.42 + 4); // 市区均速约 25 km/h
        double taxiCost = Math.round(12 + 2.4 * km);
        return new RouteInfo(Math.round(km * 10) / 10.0, walkMinutes, driveMinutes, taxiCost);
    }
}
