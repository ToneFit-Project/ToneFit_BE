package com.example.tonefitserver.domain.generation.dto;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * v0.52 API 명세 §4.1 + PM 요구사항 FUNC-De-03 — 생성 요청.
 * brief_content: 10~200자 (PM 확정).
 */
public record GenerationRequest(
        @NotNull Receiver receiverType,
        @NotNull Purpose purpose,

        @NotBlank(message = "brief_content 는 필수입니다.")
        @Size(min = 10, max = 200, message = "brief_content 는 최소 10자, 최대 200자입니다.")
        String briefContent
) {
}
