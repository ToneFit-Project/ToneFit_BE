package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.domain.correction.model.Label;

import java.util.List;

/**
 * Gemini 교정 결과. v0.5 부터 {@code corrected_email} 필드 제거 — 서버가 원문 + changes 로 재조립.
 * FE 도 동일 로직으로 재조립하므로 BE 응답에서도 corrected_email 노출하지 않음.
 */
public record AiCorrectionResult(
        List<Change> changes
) {
    /**
     * AI 가 반환하는 교정 항목.
     * start/end 오프셋은 AI 가 직접 세지 않고 서버가 original 문자열을 indexOf 로 탐색해 채운다.
     * 따라서 AI 역직렬화 시점엔 null 이며, sanitize 단계에서 서버가 값을 채워 다음 단계로 넘긴다.
     */
    public record Change(
            int index,
            Integer start,
            Integer end,
            String original,
            String corrected,
            String reason,
            Label label,
            double confidence,
            List<String> appliedRules
    ) {
    }
}
