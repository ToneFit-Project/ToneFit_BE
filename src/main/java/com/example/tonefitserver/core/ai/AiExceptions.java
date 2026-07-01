package com.example.tonefitserver.core.ai;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * AI 폴오버 로깅용 예외 유틸. CompletableFuture 체인에서 예외는 {@link CompletionException}/
 * {@link ExecutionException} 으로 감싸져 전파되므로, 진단 로그(gemini_call/gpt_call outcome=error)에
 * 실제 원인 타입(HttpTimeoutException·AiHttpException 등)을 남기려면 언랩해야 한다.
 */
public final class AiExceptions {

    private AiExceptions() {
    }

    /** CompletionException/ExecutionException 래퍼를 벗겨 실제 원인을 돌려준다(순환 방어). */
    public static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        int guard = 0;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null && cause.getCause() != cause && guard++ < 8) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** 언랩한 원인의 단순 클래스명 — 로그 error= 값. */
    public static String typeName(Throwable t) {
        return unwrap(t).getClass().getSimpleName();
    }
}
