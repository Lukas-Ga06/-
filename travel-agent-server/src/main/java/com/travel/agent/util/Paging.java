package com.travel.agent.util;

import com.travel.agent.dto.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * 列表接口的边界控制。
 *
 * 这里有两套常量，对应两种实现阶段：
 * - {@link #DEFAULT_LIMIT} / {@link #MAX_LIMIT}：历史「内存切片」{@link #slice} 的兜底上限。
 *   尚未迁移到数据库分页的调用方（照片/日程/知识版本等）仍在使用，保持原值不动，避免跨模块行为突变。
 * - {@link #DEFAULT_PAGE_LIMIT} / {@link #MAX_PAGE_LIMIT}：第九批列表分页契约
 *   （limit 默认 20、上限 200、offset 负数按 0；见 docs/proposals/batch9-contract.md §1）。
 *
 * 新代码一律用 {@link #pageable} 把 limit/offset 下推数据库，**不得再用 {@link #slice}
 * 做列表主路径**（列表数据量由 DB 决定，不先全量加载再内存截取）。
 */
public final class Paging {

    /** 历史内存切片默认条数（仅 {@link #slice} 使用） */
    public static final int DEFAULT_LIMIT = 500;
    /** 历史内存切片上限（仅 {@link #slice} 使用） */
    public static final int MAX_LIMIT = 1000;

    /** 数据库分页契约：默认条数 */
    public static final int DEFAULT_PAGE_LIMIT = 20;
    /** 数据库分页契约：单页上限 */
    public static final int MAX_PAGE_LIMIT = 200;

    private Paging() {
    }

    /**
     * 内存切片（兼容老调用方，新代码不要用）。
     * limit <= 0 用 {@link #DEFAULT_LIMIT}，超过 {@link #MAX_LIMIT} 收敛到上限。
     */
    public static <T> List<T> slice(List<T> source, int offset, int limit) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int from = Math.max(offset, 0);
        if (from >= source.size()) {
            return List.of();
        }
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int to = Math.min(from + size, source.size());
        return List.copyOf(source.subList(from, to));
    }

    /** 归一化单页条数：<= 0 用默认 20，> 200 收敛到 200（不报错）。 */
    public static int normalizeLimit(int limit) {
        return limit <= 0 ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT);
    }

    /** 归一化偏移：负数按 0。 */
    public static int normalizeOffset(int offset) {
        return Math.max(offset, 0);
    }

    /**
     * 构造下推数据库的 {@link Pageable}。
     *
     * 用自定义 {@link PageRequest} 子类而不是 {@code PageRequest.of(offset / limit, limit)}：
     * 后者只能表达「整页偏移」，客户端传 offset=1&amp;limit=2 时会错误地返回第 0 页；
     * 这里把原始 offset 保留在 {@link Pageable#getOffset()} 上，JPQL 的 setFirstResult 直接用精确值。
     */
    public static Pageable pageable(int offset, int limit, Sort sort) {
        int size = normalizeLimit(limit);
        int from = normalizeOffset(offset);
        return new OffsetPageRequest(from / size, size, from, sort == null ? Sort.unsorted() : sort);
    }

    /** 构造响应里的分页元数据；total 为满足条件的总条数（不是当前页条数）。 */
    public static ApiResponse.Pagination meta(long total, int limit, int offset) {
        int size = normalizeLimit(limit);
        int from = normalizeOffset(offset);
        long count = Math.max(total, 0);
        return new ApiResponse.Pagination(count, size, from, (long) from + size < count);
    }

    /** 精确 offset 的 PageRequest：只覆写 getOffset，分页/排序语义与 PageRequest 完全一致。 */
    private static final class OffsetPageRequest extends PageRequest {

        private static final long serialVersionUID = 1L;

        private final long offset;

        private OffsetPageRequest(int page, int size, long offset, Sort sort) {
            super(page, size, sort);
            this.offset = offset;
        }

        @Override
        public long getOffset() {
            return offset;
        }
    }
}
