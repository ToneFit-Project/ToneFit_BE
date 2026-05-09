package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Status;

import java.time.LocalDateTime;

public record FinalizeResponse(
        Long sessionId,
        Status status,
        String aiFinal,
        String aiSubject,
        LocalDateTime createdAt
) {
}
