package com.example.tonefitserver.domain.correction.ai;

/**
 * 구조 교정 결과. Gemini structured output 으로 {@code {"structure_corrected": "..."}} 단일 필드.
 * 변경 단위(changes) 가 1대1 매핑 안 되므로 변경 카드 없이 전체 텍스트만 반환.
 * (Jackson SNAKE_CASE 정책으로 structureCorrected ↔ structure_corrected 자동 변환)
 */
public record AiStructureResult(
        String structureCorrected
) {
}
