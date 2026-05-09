package com.example.tonefitserver.core.exception;

import com.example.tonefitserver.core.enums.ErrorType;
import lombok.Getter;

@Getter
public abstract class TonefitException extends RuntimeException {

    private final ErrorType errorType;
    /** AI_SERVICE_ERROR 등에서 어떤 세션이 영향을 받았는지 응답 페이로드에 함께 싣기 위한 부가 정보 */
    private final Long sessionId;

    protected TonefitException(ErrorType errorType) {
        this(errorType, errorType.getMessage(), null, null);
    }

    protected TonefitException(ErrorType errorType, String message) {
        this(errorType, message, null, null);
    }

    protected TonefitException(ErrorType errorType, String message, Long sessionId, Throwable cause) {
        super(message != null ? message : errorType.getMessage(), cause);
        this.errorType = errorType;
        this.sessionId = sessionId;
    }
}
