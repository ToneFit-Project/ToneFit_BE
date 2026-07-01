package com.example.tonefitserver.core.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI(GPT) 폴오버 fallback 설정. 폴오버 활성 시에만 사용.
 *
 * <p>구조화 출력(response_format json_schema strict)을 지원하는 모델이어야 한다. 모델 ID 는 PM 확정값
 * {@code gpt-4.1-mini}(yaml 기본값) — env({@code OPENAI_*})/AWS Secret 으로 재배포 없이 변경 가능.
 * api-key 는 시크릿이라 AWS Secrets Manager(OPENAI_API_KEY) 주입 필수. 생성·교정 모델 분리(필요 시 동일).
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        String baseUrl,
        String generationModel,
        String correctionModel
) {
}
