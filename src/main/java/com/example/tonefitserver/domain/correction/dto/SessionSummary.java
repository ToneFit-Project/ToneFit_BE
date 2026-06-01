package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import com.example.tonefitserver.domain.session.Status;

import java.time.LocalDateTime;

/**
 * 진행 중/완료 이력 목록 요약. v0.5 부터 subject 필드 사라짐.
 */
public record SessionSummary(
        Long sessionId,
        Receiver receiverType,
        Purpose purpose,
        Status status,
        String originalPreview,
        LocalDateTime createdAt
) {
}
