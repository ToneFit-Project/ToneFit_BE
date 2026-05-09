package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.correction.model.Action;

import java.time.LocalDateTime;

public record RejectResponse(
        Long sessionId,
        int index,
        Action action,
        LocalDateTime updatedAt
) {
}
