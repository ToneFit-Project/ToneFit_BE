package com.example.tonefitserver.domain.correction.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 설정. API 키는 하나로 공유 — 모델은 호출 경로 파라미터일 뿐.
 *
 * <p>{@code lightModel} 은 회신의 요약·파악·점검 보조 단계용 저가 모델 (FUNC-Rep-15).
 * 비워두면 {@code model}(작성·교정·생성용 메인 모델)로 fallback.
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String model,
        String lightModel,
        String baseUrl
) {
    /** 보조 단계용 모델 — 미설정 시 메인 모델 사용. */
    public String lightModelOrDefault() {
        return (lightModel == null || lightModel.isBlank()) ? model : lightModel;
    }
}
