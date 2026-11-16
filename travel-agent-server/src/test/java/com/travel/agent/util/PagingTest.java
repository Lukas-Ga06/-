package com.travel.agent.util;

import com.travel.agent.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 列表边界：默认上限、offset 切片、limit 越界收敛、分页元数据 */
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

    // ---------- 第九批数据库分页契约 ----------

    @Test
    @DisplayName("分页契约：limit 默认 20、上限 200，offset 负数按 0")
    void normalizesPageParams() {
        assertThat(Paging.normalizeLimit(0)).isEqualTo(20);
        assertThat(Paging.normalizeLimit(-3)).isEqualTo(20);
        assertThat(Paging.normalizeLimit(7)).isEqualTo(7);
        assertThat(Paging.normalizeLimit(200)).isEqualTo(200);
        assertThat(Paging.normalizeLimit(201)).isEqualTo(200);
        assertThat(Paging.normalizeLimit(999999)).isEqualTo(200);

        assertThat(Paging.normalizeOffset(-5)).isZero();
        assertThat(Paging.normalizeOffset(40)).isEqualTo(40);
    }

    @Test
    @DisplayName("pageable 保留精确 offset（不是整页倍数时也不取整）")
    void pageableKeepsExactOffset() {
        Sort sort = Sort.by(Sort.Order.desc("id"));

        Pageable firstPage = Paging.pageable(0, 20, sort);
        assertThat(firstPage.getOffset()).isZero();
        assertThat(firstPage.getPageSize()).isEqualTo(20);
        assertThat(firstPage.getSort()).isEqualTo(sort);

        Pageable oddOffset = Paging.pageable(1, 2, sort);
        assertThat(oddOffset.getOffset()).isEqualTo(1);
        assertThat(oddOffset.getPageSize()).isEqualTo(2);

        Pageable clamped = Paging.pageable(400, 999999, null);
        assertThat(clamped.getOffset()).isEqualTo(400);
        assertThat(clamped.getPageSize()).isEqualTo(200);
        assertThat(clamped.getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    @DisplayName("meta 计算 total/limit/offset/hasMore")
    void buildsPaginationMeta() {
        ApiResponse.Pagination empty = Paging.meta(0, 20, 0);
        assertThat(empty.total()).isZero();
        assertThat(empty.limit()).isEqualTo(20);
        assertThat(empty.offset()).isZero();
        assertThat(empty.hasMore()).isFalse();

        ApiResponse.Pagination firstPage = Paging.meta(45, 20, 0);
        assertThat(firstPage.total()).isEqualTo(45);
        assertThat(firstPage.hasMore()).isTrue();

        ApiResponse.Pagination middlePage = Paging.meta(45, 20, 40);
        assertThat(middlePage.offset()).isEqualTo(40);
        assertThat(middlePage.hasMore()).isFalse();

        ApiResponse.Pagination lastPage = Paging.meta(45, 20, 20);
        assertThat(lastPage.hasMore()).isTrue();

        ApiResponse.Pagination oversized = Paging.meta(1000, 999999, -1);
        assertThat(oversized.limit()).isEqualTo(200);
        assertThat(oversized.offset()).isZero();
        assertThat(oversized.hasMore()).isTrue();
    }
}
