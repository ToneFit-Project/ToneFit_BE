package com.example.tonefitserver.core.dto.auth;

import com.example.tonefitserver.core.enums.Plan;

public record AnonymousResponse(
        Long userId,
        boolean isGuest,
        Plan plan,
        String anonymousToken,
        String accessToken,
        String refreshToken
) {
}
