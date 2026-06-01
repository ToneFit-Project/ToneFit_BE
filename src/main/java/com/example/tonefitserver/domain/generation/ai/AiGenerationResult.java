package com.example.tonefitserver.domain.generation.ai;

/**
 * 생성 결과. Gemini structured output: {@code {"generated_subject": ..., "generated_email": ...}}.
 * (Jackson SNAKE_CASE 정책으로 generatedSubject ↔ generated_subject 자동 변환)
 */
public record AiGenerationResult(
        String generatedSubject,
        String generatedEmail
) {
}
