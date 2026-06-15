package com.example.tonefitserver.core.dto.auth;

import com.example.tonefitserver.core.enums.Plan;

/**
 * Google OAuth 응답 body. refresh_token 은 HttpOnly Cookie 로 별도 발급되므로 body 에서 제외.
 *
 * <p>v0.5 PM 결정으로 free_used 제거 — Generation 무료 한도는 FE/localStorage 관리.
 * nickname 은 Google 프로필 표시 이름 (FUNC-Au-02 #2).
 */
public record GoogleAuthResponse(
        Long userId,
        String email,
        String nickname,
        String profileImageUrl,
        String provider,
        Plan plan,
        int creditBalance,
        String accessToken
) {
}
