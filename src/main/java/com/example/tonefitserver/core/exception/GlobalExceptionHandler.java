package com.example.tonefitserver.core.exception;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 미허용 HTTP 메서드 — 컨트롤러에 매핑되지 않은 method 로 요청 시.
     * 예: GET /api/v1/corrections (POST 만 매핑됨), DELETE /api/v1/corrections/{id} 등.
     * Allow 헤더에 실제 지원 메서드 목록을 함께 반환.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: requested={}, supported={}", e.getMethod(), e.getSupportedHttpMethods());
        ErrorType errorType = ErrorType.METHOD_NOT_ALLOWED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(errorType.getStatus());
        if (e.getSupportedHttpMethods() != null && !e.getSupportedHttpMethods().isEmpty()) {
            builder.allow(e.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return builder.body(ApiResponse.error(errorType.getCode(), errorType.getMessage()));
    }

    /**
     * 미지원 Content-Type — Content-Type 헤더가 없거나 application/json 외 타입으로 POST/PUT/PATCH 등 요청 시.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<?>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("Unsupported media type: contentType={}, supported={}", e.getContentType(), e.getSupportedMediaTypes());
        ErrorType errorType = ErrorType.UNSUPPORTED_MEDIA_TYPE;
        return ResponseEntity
                .status(errorType.getStatus())
                .body(ApiResponse.error(errorType.getCode(),
                        "지원하지 않는 Content-Type 입니다. application/json 으로 요청해주세요."));
    }

    /**
     * 경로 변수/쿼리 파라미터의 타입 변환 실패.
     * 예: GET /api/v1/corrections/abc — sessionId 가 Long 인데 문자열 전달 시.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String requiredType = e.getRequiredType() == null ? "?" : e.getRequiredType().getSimpleName();
        log.warn("Argument type mismatch: param={}, value={}, requiredType={}",
                e.getName(), e.getValue(), requiredType);
        return ResponseEntity
                .status(ErrorType.INVALID_REQUEST.getStatus())
                .body(ApiResponse.error(ErrorType.INVALID_REQUEST.getCode(),
                        String.format("'%s' 파라미터에 잘못된 값이 전달되었습니다.", e.getName())));
    }

    /**
     * 매핑되지 않은 경로. Spring Boot 6.1+ 의 ResourceHttpRequestHandler 가 던지는 표준 예외.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNoResource(NoResourceFoundException e) {
        log.warn("No resource found: {}", e.getResourcePath());
        return ResponseEntity
                .status(ErrorType.NOT_FOUND.getStatus())
                .body(ApiResponse.error(ErrorType.NOT_FOUND.getCode(), "요청한 경로를 찾을 수 없습니다."));
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
