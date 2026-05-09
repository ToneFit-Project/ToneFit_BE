package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.domain.correction.model.Label;

import java.util.List;

public record AiCorrectionResult(
        String correctedEmail,
        List<Change> changes
) {
    /**
     * AI가 반환하는 교정 항목.
     * start/end 오프셋은 AI가 직접 세지 않고 서버가 original 문자열을 indexOf로 탐색해 채운다.
     * 따라서 AI 역직렬화 시점엔 null이며, sanitize 단계에서 서버가 값을 채워 다음 단계로 넘긴다.
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
