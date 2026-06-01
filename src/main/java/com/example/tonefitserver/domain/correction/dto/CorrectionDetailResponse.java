package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.correction.model.Action;
import com.example.tonefitserver.domain.correction.model.Label;
import com.example.tonefitserver.domain.correction.model.ReasonPrimary;
import com.example.tonefitserver.domain.correction.model.ReasonSecondary;
import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import com.example.tonefitserver.domain.session.Status;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /corrections/{id} 응답. v0.5 부터 subject/aiFinal/aiSubject/userSubject/structureCorrected
 * 모두 제거 (필드 자체가 사라짐). user_final 은 CONFIRMED 상태에서만 채워짐.
 */
public record CorrectionDetailResponse(
        Long sessionId,
        Receiver receiverType,
        Purpose purpose,
        String originalEmail,
        String userFinal,
        Status status,
        List<FeedbackItem> feedbacks,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record FeedbackItem(
            int index,
            int start,
            int end,
            String original,
            String corrected,
            String reason,
            Label label,
            double confidence,
            List<String> appliedRules,
            Action action,
            ReasonPrimary reasonPrimary,
            ReasonSecondary reasonSecondary,
            String reasonText
    ) {
    }
}
