package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import com.example.tonefitserver.domain.session.Status;

import java.time.LocalDateTime;

public record SessionSummary(
        Long sessionId,
        Receiver receiverType,
        Purpose purpose,
        String subject,
        Status status,
        String originalPreview,
        LocalDateTime createdAt
) {
}
