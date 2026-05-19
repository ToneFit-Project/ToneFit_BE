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

public record CorrectionDetailResponse(
        Long sessionId,
        Receiver receiverType,
        Purpose purpose,
        String subject,
        String originalEmail,
        /** 구조 교정이 적용된 본문. null 이면 구조 교정 미사용 세션. */
        String structureCorrected,
        String aiFinal,
        String userFinal,
        String aiSubject,
        String userSubject,
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
