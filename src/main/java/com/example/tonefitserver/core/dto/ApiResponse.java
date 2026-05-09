package com.example.tonefitserver.core.dto;

public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorResponse error
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<?> error(String code, String message) {
        return error(code, message, null);
    }

    public static ApiResponse<?> error(String code, String message, Long sessionId) {
        return new ApiResponse<>(false, null, new ErrorResponse(code, message, sessionId));
    }

    public record ErrorResponse(
            String code,
            String message,
            Long sessionId
    ) {
    }
}
