package com.travel.agent.util;

/**
 * WGS-84 与 GCJ-02 互转。
 *
 * <p>全仓坐标契约：存储 / API / 业务计算一律 WGS-84；只有调用高德服务或渲染高德底图时
 * 才在本层转换成 GCJ-02。公式与前端 {@code travel-agent-web/src/lib/coords.ts} 保持一致。
 *
 * <p>境外坐标不做偏移（高德对境外同样不纠偏），返回原值。
 */
public final class CoordinateTransform {

    private static final double PI = Math.PI;
    /** 克拉索夫斯基椭球长半轴 */
    private static final double AXIS = 6378245.0;
    /** 椭球偏心率平方 */
    private static final double EE = 0.00669342162296594323;

    private CoordinateTransform() {
    }

    /** WGS-84 → GCJ-02，返回 {@code [lat, lng]}；境外原样返回 */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = dLat * 180.0 / (AXIS * (1 - EE) / (magic * sqrtMagic) * PI);
        dLng = dLng * 180.0 / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /** GCJ-02 → WGS-84，返回 {@code [lat, lng]}；三次不动点迭代，境内误差远小于一个像素 */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || outOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double wLat = lat;
        double wLng = lng;
        for (int i = 0; i < 3; i++) {
            double[] gcj = wgs84ToGcj02(wLat, wLng);
            wLat += lat - gcj[0];
            wLng += lng - gcj[1];
        }
        return new double[]{wLat, wLng};
    }

    /**
     * 解析高德接口的 {@code location} 字段（格式 {"经度,纬度"}，GCJ-02）为 WGS-84 {@code [lat, lng]}。
     *
     * <p>约定：格式非法或经纬度都为 0（高德对无结果常返回 "0,0"）时返回 {@code null}，
     * 由调用方按「定位失败」处理。
     */
    public static double[] parseGcj02Location(String location) {
        if (location == null) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            if (lat == 0 && lng == 0) {
                return null;
            }
            return gcj02ToWgs84(lat, lng);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}