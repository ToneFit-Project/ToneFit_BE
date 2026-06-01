package com.example.tonefitserver.domain.correction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 송신 시점에 호출하는 confirm 요청. v0.5 부터 제목 필드 사라짐 — user_final 만 필수.
 */
public record ConfirmRequest(
        @NotBlank(message = "user_final 은 필수입니다.")
        @Size(max = 4000, message = "user_final 은 최대 4,000자입니다.")
        String userFinal
) {
}
