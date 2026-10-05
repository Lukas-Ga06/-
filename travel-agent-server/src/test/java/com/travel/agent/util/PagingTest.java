package com.travel.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 列表边界：默认上限、offset 切片、limit 越界收敛 */
class PagingTest {

    private static List<Integer> numbers(int count) {
        return IntStream.range(0, count).boxed().toList();
    }

    @Test
    @DisplayName("按 offset/limit 切片")
    void slicesByOffsetAndLimit() {
        List<Integer> source = numbers(10);

        assertThat(Paging.slice(source, 0, 3)).containsExactly(0, 1, 2);
        assertThat(Paging.slice(source, 3, 3)).containsExactly(3, 4, 5);
        assertThat(Paging.slice(source, 9, 3)).containsExactly(9);
    }

    @Test
    @DisplayName("offset 越界返回空列表，负数按 0 处理")
    void handlesOutOfRangeOffset() {
        List<Integer> source = numbers(3);

        assertThat(Paging.slice(source, 99, 10)).isEmpty();
        assertThat(Paging.slice(source, -5, 2)).containsExactly(0, 1);
    }

    @Test
    @DisplayName("limit 为 0 用默认值，超过上限收敛到上限")
    void clampsLimit() {
        List<Integer> small = numbers(5);
        assertThat(Paging.slice(small, 0, 0)).hasSize(5);

        List<Integer> big = numbers(Paging.MAX_LIMIT + 50);
        assertThat(Paging.slice(big, 0, Paging.MAX_LIMIT + 500)).hasSize(Paging.MAX_LIMIT);
    }

    @Test
    @DisplayName("空列表安全")
    void handlesEmpty() {
        assertThat(Paging.slice(List.of(), 0, 10)).isEmpty();
        assertThat(Paging.slice(null, 0, 10)).isEmpty();
    }
}
