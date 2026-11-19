package com.travel.agent.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁死 {@code route-legs} 的 polyline JSON 契约（前端与文档按此实现）：
 * <pre>{@code "polyline": [[lat, lng], ...]}</pre>
 * <ul>
 *   <li>外层数组的元素是「点」，每个点是长度 2 的 {@code [纬度, 经度]}（**纬度在前**，WGS-84）；</li>
 *   <li>无几何时是空数组 {@code []} 而不是 null，前端可无条件 {@code leg.polyline.length} 判断；</li>
 *   <li>字段名固定为 {@code polyline}。</li>
 * </ul>
 */
class RouteLegPolylineJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void polylineIsArrayOfLatLngPairs() throws Exception {
        ItineraryDtos.RouteLegDTO leg = ItineraryDtos.RouteLegDTO.builder()
                .dayIndex(1)
                .sequence(1)
                .source("amap")
                .polyline(List.of(new double[]{31.2247502775, 121.4858355938},
                        new double[]{31.2400000000, 121.5000000000}))
                .build();

        String json = objectMapper.writeValueAsString(leg);
        assertThat(json).contains("\"polyline\":[[31.2247502775,121.4858355938],[31.24,121.5]]");
    }

    @Test
    void polylineWithoutGeometrySerializesAsEmptyArray() throws Exception {
        ItineraryDtos.RouteLegDTO leg = ItineraryDtos.RouteLegDTO.builder()
                .dayIndex(1)
                .sequence(1)
                .source("estimated")
                .polyline(List.of())
                .build();

        assertThat(objectMapper.writeValueAsString(leg)).contains("\"polyline\":[]");
    }
}
