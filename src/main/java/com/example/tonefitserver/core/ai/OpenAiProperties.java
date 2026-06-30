package com.example.tonefitserver.core.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI(GPT) 폴오버 fallback 설정. 폴오버 활성 시에만 사용.
 *
 * <p>구조화 출력(response_format json_schema strict)을 지원하는 모델이어야 한다. 모델 ID 는 PM 확정 전
 * 임시 기본값 — env({@code OPENAI_*})/AWS Secret 으로 조정. 생성·교정 모델을 분리(필요 시 동일 지정).
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        String baseUrl,
        String generationModel,
        String correctionModel
) {
}
