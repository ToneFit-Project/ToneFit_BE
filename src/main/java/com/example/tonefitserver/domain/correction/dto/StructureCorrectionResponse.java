package com.example.tonefitserver.domain.correction.dto;

import java.time.LocalDateTime;

public record StructureCorrectionResponse(
        Long sessionId,
        String structureCorrected,
        LocalDateTime createdAt
) {
}
