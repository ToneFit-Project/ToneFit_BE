package com.example.tonefitserver.core.dto.auth;

import com.example.tonefitserver.core.enums.Plan;

/**
 * 익명 사용자 발급 응답. refresh_token 은 HttpOnly Cookie 로 별도 발급되므로 body 에서 제외.
 */
public record AnonymousResponse(
        Long userId,
        boolean isGuest,
        Plan plan,
        String anonymousToken,
        String accessToken
) {
}
