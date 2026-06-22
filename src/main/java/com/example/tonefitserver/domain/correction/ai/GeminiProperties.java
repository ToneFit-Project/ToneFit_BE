package com.example.tonefitserver.domain.correction.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 설정. API 키는 하나로 공유 — 모델은 호출 경로 파라미터일 뿐.
 *
 * <p>{@code model} 은 기본/회신 메인 모델. {@code lightModel} 은 회신의 요약·파악·점검 보조 단계용
 * 저가 모델 (FUNC-Rep-15) — 비워두면 {@code model} 로 fallback.
 *
 * <p>생성·교정은 PM 실험 결과 모델·사고수준을 각각 분리 운영한다(비용 절감). 미설정 시 {@code model} fallback.
 * <ul>
 *   <li>생성: {@code generationModel} + {@code generationThinkingBudget}(gemini-2.5 계열 — thinkingBudget 사용)</li>
 *   <li>교정: {@code correctionModel} + {@code correctionThinkingBudget} + {@code correctionThinkingLevel}
 *       (PM 재확인 — gemini-3 계열이지만 budget·level 둘 다 지정 요청)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String model,
        String lightModel,
        String baseUrl,
        String generationModel,
        Integer generationThinkingBudget,
        String correctionModel,
        Integer correctionThinkingBudget,
        String correctionThinkingLevel
) {
    /** 보조 단계용 모델 — 미설정 시 메인 모델 사용. */
    public String lightModelOrDefault() {
        return (lightModel == null || lightModel.isBlank()) ? model : lightModel;
    }

    /** 생성 전용 모델 — 미설정 시 메인 모델 사용. */
    public String generationModelOrDefault() {
        return (generationModel == null || generationModel.isBlank()) ? model : generationModel;
    }

    /** 교정 전용 모델 — 미설정 시 메인 모델 사용. */
    public String correctionModelOrDefault() {
        return (correctionModel == null || correctionModel.isBlank()) ? model : correctionModel;
    }
}
