package com.travel.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 统一 REST 响应包装 */
@Data
@NoArgsConstructor
public class ApiResponse<T> {

    public static final int CODE_OK = 0;

    private int code;
    private String message;
    private T data;

    /**
     * 列表分页元数据（第九批契约，batch9-contract.md §1）。
     * 未分页的响应保持 null，并由 {@link JsonInclude} 保证不序列化该键 —— 老客户端只读 data 不受影响。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Pagination pagination;

    public ApiResponse(int code, String message, T data) {
        this(code, message, data, null);
    }

    public ApiResponse(int code, String message, T data, Pagination pagination) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.pagination = pagination;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, null);
    }

    /** 列表接口：data 仍是数组，分页信息放在同级的 pagination 里。 */
    public static <T> ApiResponse<T> ok(T data, Pagination pagination) {
        return new ApiResponse<>(CODE_OK, "success", data, pagination);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /** 分页元数据：total=满足条件的总条数，limit/offset=归一化后的请求值，hasMore=后面是否还有数据。 */
    public record Pagination(long total, int limit, int offset, boolean hasMore) {
    }
}
