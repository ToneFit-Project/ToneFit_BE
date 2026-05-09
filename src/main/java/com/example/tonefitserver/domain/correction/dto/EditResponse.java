package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Status;

import java.time.LocalDateTime;

public record EditResponse(
        Long sessionId,
        Status status,
        LocalDateTime updatedAt
) {
}
