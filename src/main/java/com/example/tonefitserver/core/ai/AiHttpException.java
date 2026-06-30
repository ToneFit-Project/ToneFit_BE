package com.example.tonefitserver.core.ai;

/**
 * AI provider HTTP 호출이 4xx/5xx 로 응답한 경우 — 폴오버에서 "즉시 실패"로 분류된다.
 * (타임아웃·네트워크 오류는 별개로 future 가 예외 완료되며 이 타입이 아니다.)
 */
public class AiHttpException extends RuntimeException {

    private final int statusCode;

    public AiHttpException(int statusCode, String bodySnippet) {
        super("AI provider HTTP " + statusCode + (bodySnippet == null || bodySnippet.isBlank() ? "" : ": " + bodySnippet));
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
