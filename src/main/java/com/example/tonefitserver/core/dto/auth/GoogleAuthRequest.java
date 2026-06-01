package com.example.tonefitserver.core.dto.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Google Identity Services 가 발급한 ID token 을 BE 에 제출하는 요청.
 *
 * <p>{@code terms_agreements} 는 신규 가입·게스트 전환 시 필수, 기존 로그인 시 무시된다.
 * 비어 있는 경우 신규 가입 분기에서 {@code TERMS_AGREEMENT_REQUIRED} 로 거부된다.
 */
public record GoogleAuthRequest(
        @NotBlank(message = "id_token 은 필수입니다.")
        String idToken,

        @Valid
        List<TermsAgreementDto> termsAgreements
) {
}
