package com.travel.agent.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 路径几何简化：Douglas-Peucker（折线抽稀）。
 *
 * <p>为什么需要它：高德驾车路径的 {@code steps[].polyline} 一条路线常有几百到几千个点，
 * 原样塞进 {@code RouteInfo} 会显著放大路线缓存与 <code>route-legs</code> 响应体；
 * 前端地图在同一缩放级别下也用不到 10 米级的点间距。按 10~15 米容差抽稀，
 * 画出来的道路形状与原始折线肉眼无差异，但点数降一个数量级。
 *
 * <p>坐标系无关：本类只做平面近似距离（{@value #METERS_PER_DEGREE} 米/度，经度按 cos(lat) 收缩），
 * 不做坐标转换——调用方负责先用 {@link CoordinateTransform} 把 GCJ-02 转成 WGS-84。
 * 平面近似在「单条城市道路几公里」的尺度上误差远小于简化容差本身，足够做抽稀判据。
 *
 * <p>点格式沿用全仓约定：{@code [lat, lng]}（十进制度）。
 */
public final class PolylineSimplify {

    /** 纬度 1 度对应的米数（WGS-84 平均值；经度再乘 cos(纬度)） */
    static final double METERS_PER_DEGREE = 111320.0;

    /** 容差翻倍的次数上限：先按标称容差抽稀，超点数再逐级放大，最多放大 2^8 倍 */
    private static final int MAX_TOLERANCE_DOUBLINGS = 8;

    private PolylineSimplify() {
    }

    /**
     * 简化折线：先按容差做 Douglas-Peucker；若点数仍超过 {@code maxPoints}，逐级放大容差；
     * 仍超限则按等间隔取点（**始终保留首尾点**，否则路线会断开）。
     *
     * @param points        输入点（{@code [lat, lng]}），允许 null / 空 / 单点
     * @param toleranceMeters 抽稀容差（米）
     * @param maxPoints     输出点数上限（至少 2）
     * @return 新的点列表（不修改入参）；输入为空时返回空列表
     */
    public static List<double[]> simplify(List<double[]> points, double toleranceMeters, int maxPoints) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        if (points.size() <= 2) {
            return List.copyOf(points);
        }
        int limit = Math.max(2, maxPoints);
        double tolerance = Math.max(0.0, toleranceMeters);
        List<double[]> simplified = douglasPeucker(points, tolerance);
        for (int i = 0; i < MAX_TOLERANCE_DOUBLINGS && simplified.size() > limit; i++) {
            tolerance = tolerance <= 0 ? 1.0 : tolerance * 2;
            simplified = douglasPeucker(points, tolerance);
        }
        return simplified.size() > limit ? capCount(simplified, limit) : simplified;
    }

    /** 经典 Douglas-Peucker；用显式栈代替递归，避免超长折线（几千点）在深递归下爆栈 */
    private static List<double[]> douglasPeucker(List<double[]> points, double toleranceMeters) {
        int n = points.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        Deque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[]{0, n - 1});
        while (!pending.isEmpty()) {
            int[] range = pending.pop();
            int first = range[0];
            int last = range[1];
            double maxDistance = -1;
            int farthest = -1;
            for (int i = first + 1; i < last; i++) {
                double distance = perpendicularMeters(points.get(i), points.get(first), points.get(last));
                if (distance > maxDistance) {
                    maxDistance = distance;
                    farthest = i;
                }
            }
            if (farthest > 0 && maxDistance > toleranceMeters) {
                keep[farthest] = true;
                if (farthest - first > 1) {
                    pending.push(new int[]{first, farthest});
                }
                if (last - farthest > 1) {
                    pending.push(new int[]{farthest, last});
                }
            }
        }
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    /** 点到线段的平面近似距离（米）；首尾重合时退化为点到点距离 */
    private static double perpendicularMeters(double[] point, double[] from, double[] to) {
        double cos = Math.cos(Math.toRadians((from[0] + to[0]) / 2));
        double px = (point[1] - from[1]) * METERS_PER_DEGREE * cos;
        double py = (point[0] - from[0]) * METERS_PER_DEGREE;
        double bx = (to[1] - from[1]) * METERS_PER_DEGREE * cos;
        double by = (to[0] - from[0]) * METERS_PER_DEGREE;
        double lengthSquared = bx * bx + by * by;
        if (lengthSquared == 0) {
            return Math.hypot(px, py);
        }
        double t = (px * bx + py * by) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - t * bx, py - t * by);
    }

    /** 保首尾的等间隔抽样：只在「容差放到 2^8 倍仍超限」的极端情况下兜底 */
    private static List<double[]> capCount(List<double[]> points, int maxPoints) {
        int n = points.size();
        List<double[]> result = new ArrayList<>(maxPoints);
        for (int i = 0; i < maxPoints; i++) {
            int index = (int) Math.round((double) i * (n - 1) / (maxPoints - 1));
            result.add(points.get(index));
        }
        return result;
    }
}
