package com.example.tonefitserver.core.exception;

import com.example.tonefitserver.core.enums.ErrorType;

public class BusinessException extends TonefitException {

    public BusinessException(ErrorType errorType) {
        super(errorType);
    }

    public BusinessException(ErrorType errorType, String message) {
        super(errorType, message);
    }

    public BusinessException(ErrorType errorType, String message, Long sessionId) {
        super(errorType, message, sessionId, null);
    }

    public BusinessException(ErrorType errorType, String message, Long sessionId, Throwable cause) {
        super(errorType, message, sessionId, cause);
    }
}
