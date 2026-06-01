package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.AnonymousResponse;

/**
 * 익명 발급 결과 — 응답 body 와 cookie 로 발급할 refresh token 을 함께 전달.
 */
public record AnonymousResult(
        AnonymousResponse body,
        String refreshToken
) {
}
