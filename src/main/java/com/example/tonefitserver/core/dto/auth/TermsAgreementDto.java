package com.example.tonefitserver.core.dto.auth;

import com.example.tonefitserver.core.enums.TermsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 신규 가입(또는 게스트 → 정식 전환) 시 함께 제출하는 약관 동의 1건.
 * 필수 약관에 {@code agreed=false} 가 오면 {@code TERMS_AGREEMENT_REQUIRED} 로 거부된다.
 */
public record TermsAgreementDto(
        @NotNull(message = "약관 종류는 필수입니다.")
        TermsType type,

        @NotBlank(message = "약관 버전은 필수입니다.")
        String version,

        @NotNull(message = "약관 동의 여부는 필수입니다.")
        Boolean agreed
) {
}
