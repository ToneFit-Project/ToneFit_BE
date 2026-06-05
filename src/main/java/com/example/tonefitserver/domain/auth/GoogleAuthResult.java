package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.GoogleAuthResponse;

/**
 * Google OAuth 처리 결과 — 응답 body 와 HTTP status 결정용 newUser 플래그.
 * refresh token 폐지로 carrier 에서 refreshToken 제거 (access token 은 body 에 포함).
 */
public record GoogleAuthResult(
        GoogleAuthResponse body,
        boolean newUser
) {
}
