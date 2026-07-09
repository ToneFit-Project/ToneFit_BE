package com.example.tonefitserver.core.dto.auth;

/**
 * 갱신 응답 — 새 access + 회전된 새 refresh (RTR). FE 는 두 값 모두 교체 저장해야 한다
 * (이전 refresh 는 소진됨 — 재사용 시 family 전체 철회).
 */
public record RefreshResponse(
        String accessToken,
        String refreshToken
) {
}
