package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Status;

import java.time.LocalDateTime;

public record ConfirmResponse(
        Long sessionId,
        Status status,
        LocalDateTime updatedAt
) {
}
