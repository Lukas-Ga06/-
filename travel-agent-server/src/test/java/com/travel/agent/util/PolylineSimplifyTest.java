package com.travel.agent.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 折线抽稀（Douglas-Peucker）：城市道路几何在 10~15 米容差下形状不变，
 * 但点数要降下来；任何情况下**首尾点必须保留**（否则路线会断开）。
 */
class PolylineSimplifyTest {

    /** 沿一条直线等分：所有中间点到线段距离为 0，抽稀后应只剩首尾 */
    @Test
    void collinearPointsReduceToEndpoints() {
        List<double[]> line = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            line.add(new double[]{31.2 + i * 0.0001, 121.5 + i * 0.0001});
        }
        List<double[]> result = PolylineSimplify.simplify(line, 12.0, 200);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(line.get(0));
        assertThat(result.get(result.size() - 1)).isEqualTo(line.get(line.size() - 1));
    }

    /** 偏离直线 ≤ 容差的抖动应被抹平：这是抽稀的主要目的（GPS/道路点的细微抖动） */
    @Test
    void tinyWiggleBelowToleranceIsSmoothedAway() {
        List<double[]> wiggly = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            // 0.00002 度 ≈ 2.2 米，远小于 12 米容差
            wiggly.add(new double[]{31.2 + (i % 2 == 0 ? 0 : 0.00002), 121.5 + i * 0.0005});
        }
        List<double[]> result = PolylineSimplify.simplify(wiggly, 12.0, 200);
        assertThat(result).hasSizeLessThan(wiggly.size());
        assertThat(result.get(0)).isEqualTo(wiggly.get(0));
        assertThat(result.get(result.size() - 1)).isEqualTo(wiggly.get(wiggly.size() - 1));
    }

    /** 真实弯道（偏离远超容差）必须保留：不能为了压点数把道路形状抹成直线 */
    @Test
    void realCurveAboveToleranceIsKept() {
        List<double[]> curve = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            double angle = Math.PI * i / 40;
            curve.add(new double[]{31.2 + 0.01 * Math.sin(angle), 121.5 + i * 0.001});
        }
        List<double[]> result = PolylineSimplify.simplify(curve, 12.0, 200);
        assertThat(result.size()).isGreaterThan(2);
        assertThat(result.get(0)).isEqualTo(curve.get(0));
        assertThat(result.get(result.size() - 1)).isEqualTo(curve.get(curve.size() - 1));
    }

    /** 点数上限兜底：抽稀结果仍超上限时按等间隔抽样，**首尾点必须保留** */
    @Test
    void pointCountIsCappedWhileKeepingEndpoints() {
        List<double[]> jagged = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            // 纬度方向左右各摆 0.5 度（≈55 km）：抽稀后仍会剩若干个点
            jagged.add(new double[]{31.2 + i * 0.00002 + (i % 2 == 0 ? 0.5 : -0.5), 121.5 + i * 0.00002});
        }
        // maxPoints=2 是极限值：逼出「容差放大到上限仍超限 → 等间隔抽样」兜底路径
        List<double[]> result = PolylineSimplify.simplify(jagged, 12.0, 2);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(jagged.get(0));
        assertThat(result.get(result.size() - 1)).isEqualTo(jagged.get(jagged.size() - 1));
    }

    @Test
    void emptyAndSinglePointAreSafe() {
        assertThat(PolylineSimplify.simplify(null, 12.0, 200)).isEmpty();
        assertThat(PolylineSimplify.simplify(List.of(), 12.0, 200)).isEmpty();
        List<double[]> one = List.of(new double[]{31.2, 121.5});
        assertThat(PolylineSimplify.simplify(one, 12.0, 200)).containsExactly(new double[]{31.2, 121.5});
    }
}
