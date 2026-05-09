package com.example.tonefitserver.core.exception;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TonefitException.class)
    public ResponseEntity<ApiResponse<?>> handleTonefitException(TonefitException e) {
        ErrorType errorType = e.getErrorType();
        if (errorType.getStatus().is5xxServerError()) {
            log.error("Business Exception: {} - {} (sessionId={})",
                    errorType.getCode(), e.getMessage(), e.getSessionId(), e);
        } else {
            log.warn("Business Exception: {} - {} (sessionId={})",
                    errorType.getCode(), e.getMessage(), e.getSessionId());
        }
        return ResponseEntity
                .status(errorType.getStatus())
                .body(ApiResponse.error(errorType.getCode(), e.getMessage(), e.getSessionId()));
    }

    /**
     * 본문 파싱 실패 (잘못된 JSON, 타입 불일치, 필수 primitive 필드 누락 등)는 사용자 입력 오류이므로
     * INVALID_REQUEST 로 떨군다. 기본 핸들러로 떨어지면 500 INTERNAL_ERROR 가 되어 부적절.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
        log.warn("Malformed JSON request: {}", detail);
        return ResponseEntity
                .status(ErrorType.INVALID_REQUEST.getStatus())
                .body(ApiResponse.error(ErrorType.INVALID_REQUEST.getCode(),
                        "요청 본문 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = bindingResult.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        log.warn("Validation Exception: {}", message);
        return ResponseEntity
                .status(ErrorType.INVALID_REQUEST.getStatus())
                .body(ApiResponse.error(ErrorType.INVALID_REQUEST.getCode(), message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        ErrorType errorType = ErrorType.INTERNAL_ERROR;
        return ResponseEntity
                .status(errorType.getStatus())
                .body(ApiResponse.error(errorType.getCode(), errorType.getMessage()));
    }
}
