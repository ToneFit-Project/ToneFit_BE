package com.example.tonefitserver.core.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** 갱신·로그아웃 요청 — body 로 refresh token 원문을 받는다 (chrome.storage.local 보관분). */
public record RefreshRequest(
        @NotBlank(message = "refresh_token 은 필수입니다.")
        String refreshToken
) {
}
