package com.travel.agent.util;

import java.util.List;

/**
 * 列表接口的边界控制。
 *
 * 目前是内存切片（数据集规模小），目的是给响应体积兜底：
 * 客户端不传 limit 时也会被限制在 {@link #DEFAULT_LIMIT} 条以内。
 * 真正的数据库分页随索引一起做（见 docs/DEVELOPMENT.md 待办）。
 */
public final class Paging {

    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 1000;

    private Paging() {
    }

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
}
