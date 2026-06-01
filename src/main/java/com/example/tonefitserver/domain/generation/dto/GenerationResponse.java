package com.example.tonefitserver.domain.generation.dto;

/**
 * v0.52 API 명세 §4.1 + PM 후속 결정 — 생성 응답.
 * PM 요구사항 FUNC-De-04 #4: 체험 횟수는 FE/localStorage 관리. BE 응답에서도 free_used/free_limit 제거.
 */
public record GenerationResponse(
        String generatedSubject,
        String generatedEmail
) {
}
