package com.travel.agent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}