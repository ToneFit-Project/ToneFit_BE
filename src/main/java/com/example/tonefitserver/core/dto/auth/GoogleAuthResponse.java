package com.example.tonefitserver.core.dto.auth;

import com.example.tonefitserver.core.enums.Plan;

/**
 * Google OAuth 응답 body. refresh_token 도 body 로 발급한다(RTR 재도입, V26) —
 * FE 는 access 와 함께 {@code chrome.storage.local} 에 보관하고, access 만료 시
 * {@code POST /auth/refresh} 로 갱신한다.
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
        String accessToken,
        String refreshToken
) {
}
