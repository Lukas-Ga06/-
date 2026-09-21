package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 统一 REST 响应包装 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    public static final int CODE_OK = 0;

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_OK, "success", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
