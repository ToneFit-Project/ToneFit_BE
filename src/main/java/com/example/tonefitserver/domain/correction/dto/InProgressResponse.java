package com.example.tonefitserver.domain.correction.dto;

import java.util.List;

public record InProgressResponse(
        List<SessionSummary> sessions
) {
}
