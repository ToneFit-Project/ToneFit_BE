package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.GoogleAuthResponse;

/**
 * Google OAuth 처리 결과 — body 와 refresh token, status 결정용 newUser 플래그를 함께 전달.
 *
 * <p>Controller 가 {@link #newUser()} 로 응답 status(201/200)를, {@link #refreshToken()} 으로
 * Set-Cookie 헤더를 결정한다. {@link #body()} 는 응답 body 그대로.
 */
public record GoogleAuthResult(
        GoogleAuthResponse body,
        String refreshToken,
        boolean newUser
) {
}
