package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.correction.model.Action;
import com.example.tonefitserver.domain.correction.model.Label;

import java.time.LocalDateTime;
import java.util.List;

public record CorrectionResponse(
        Long sessionId,
        String correctedEmail,
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
