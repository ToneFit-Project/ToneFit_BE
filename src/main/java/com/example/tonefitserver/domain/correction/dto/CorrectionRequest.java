package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CorrectionRequest(
        @NotNull Receiver receiverType,
        @NotNull Purpose purpose,
        @Size(max = 255, message = "제목은 최대 255자입니다.")
        String subject,
        // FUNC-LLM-07: FE 1차 검증과 별개로 BE 방어선. 원문 최소 10자, 최대 2,000자.
        @NotBlank(message = "원문은 필수입니다.")
        @Size(min = 10, max = 2000, message = "원문은 최소 10자, 최대 2,000자입니다.")
        String originalEmail,
        List<ProtectedRange> protectedRanges
) {
    public record ProtectedRange(int start, int end) {
    }
}
