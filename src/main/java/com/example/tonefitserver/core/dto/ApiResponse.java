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
        return error(code, message, null, null);
    }

    public static ApiResponse<?> error(String code, String message, Long sessionId) {
        return error(code, message, sessionId, null);
    }

    public static ApiResponse<?> error(String code, String message, Long sessionId, Object details) {
        return new ApiResponse<>(false, null, new ErrorResponse(code, message, sessionId, details));
    }

    /**
     * 오류 응답 body. sessionId 와 details 는 case-별 부가 정보로 nullable.
     * details 의 구조는 ErrorType 별로 정의된다. 예) TERMS_AGREEMENT_REQUIRED →
     * {@code {"missing_terms": ["SERVICE", "PRIVACY", "ANALYTICS"]}}.
     */
    public record ErrorResponse(
            String code,
            String message,
            Long sessionId,
            Object details
    ) {
    }
}
