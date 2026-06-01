package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.correction.model.Action;
import com.example.tonefitserver.domain.correction.model.Label;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 교정 응답. v0.5 부터 corrected_email 제거 — FE 가 원문 + changes 로 재조립.
 */
public record CorrectionResponse(
        Long sessionId,
        List<ChangeItem> changes,
        LocalDateTime createdAt
) {
    public record ChangeItem(
            int index,
            int start,
            int end,
            String original,
            String corrected,
            String reason,
            Label label,
            double confidence,
            List<String> appliedRules,
            Action action
    ) {
    }
}
