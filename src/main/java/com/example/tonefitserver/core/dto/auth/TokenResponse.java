package com.example.tonefitserver.core.dto.auth;

/**
 * AuthService 내부에서 access/refresh 토큰 쌍을 전달할 때 쓰는 carrier.
 * isGuest 는 cookie Max-Age 분기에 필요해서 함께 실어 보낸다.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        boolean isGuest
) {
}
