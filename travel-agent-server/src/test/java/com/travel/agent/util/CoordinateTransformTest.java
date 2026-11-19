package com.travel.agent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 坐标转换：与前端 coords.ts 使用同一套公式，保证前后端对「一个点」的理解一致。 */
class CoordinateTransformTest {

    @Test
    void wgs84ToGcj02MatchesShanghaiReference() {
        double[] gcj = CoordinateTransform.wgs84ToGcj02(31.2304, 121.4737);
        assertEquals(31.2284577376, gcj[0], 1e-9);
        assertEquals(121.4782230593, gcj[1], 1e-9);
    }

    @Test
    void gcj02RoundTripIsSubMeter() {
        double[] gcj = CoordinateTransform.wgs84ToGcj02(31.2397, 121.4906);
        double[] wgs = CoordinateTransform.gcj02ToWgs84(gcj[0], gcj[1]);
        assertEquals(31.2397, wgs[0], 1e-6);
        assertEquals(121.4906, wgs[1], 1e-6);
    }

    @Test
    void overseasCoordinatesAreNotShifted() {
        double[] paris = CoordinateTransform.wgs84ToGcj02(48.8566, 2.3522);
        assertArrayEquals(new double[]{48.8566, 2.3522}, paris, 1e-12);
        double[] tokyo = CoordinateTransform.gcj02ToWgs84(35.6762, 139.6503);
        assertArrayEquals(new double[]{35.6762, 139.6503}, tokyo, 1e-12);
    }

    /**
     * 往返收敛到米级：真实道路 polyline 的每个点都走「高德 GCJ-02 → WGS-84」，
     * 反向转换精度不够会让整条路线整体偏移。这里对多个城市的坐标做往返验证。
     */
    @Test
    void gcj02ToWgs84RoundTripConvergesWithinOneMeter() {
        double[][] wgs84Points = {
                {31.2304, 121.4737},   // 上海
                {39.909187, 116.397463}, // 北京（验收报告里真实地理编码结果）
                {22.543099, 114.057868}, // 深圳
                {45.75, 126.65},       // 哈尔滨（高纬度，cos(lat) 影响最大）
        };
        for (double[] wgs : wgs84Points) {
            double[] gcj = CoordinateTransform.wgs84ToGcj02(wgs[0], wgs[1]);
            double[] back = CoordinateTransform.gcj02ToWgs84(gcj[0], gcj[1]);
            double dy = (back[0] - wgs[0]) * 111320.0;
            double dx = (back[1] - wgs[1]) * 111320.0 * Math.cos(Math.toRadians(wgs[0]));
            assertTrue(Math.hypot(dx, dy) < 1.0,
                    "往返误差应在米级以内: " + wgs[0] + "," + wgs[1] + " -> " + Math.hypot(dx, dy) + "m");
        }
    }
}
