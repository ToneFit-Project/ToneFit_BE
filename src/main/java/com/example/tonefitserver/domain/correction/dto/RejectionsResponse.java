package com.example.tonefitserver.domain.correction.dto;

/**
 * 거절 교정 항목 보존 결과. {@code stored} 는 실제 저장된 건수 —
 * AI_LEARNING 미동의자는 보존하지 않으므로 0 이 반환된다(요청 자체는 성공 200).
 */
public record RejectionsResponse(int stored) {
}
