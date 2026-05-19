package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StructureCorrectionRequest(
        @NotNull Receiver receiverType,
        @NotNull Purpose purpose,
        // FUNC-LLM-07 동일 정책. protected_ranges 는 미지원.
        @NotBlank(message = "원문은 필수입니다.")
        @Size(min = 10, max = 2000, message = "원문은 최소 10자, 최대 2,000자입니다.")
        String originalEmail
) {
}
