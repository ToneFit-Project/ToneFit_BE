package com.example.tonefitserver.core.dto.auth;

import com.example.tonefitserver.core.enums.Plan;

public record LoginResponse(
        Long userId,
        String email,
        boolean isGuest,
        Plan plan,
        int correctionsUsedToday,
        int creditBalance,
        String accessToken,
        String refreshToken
) {
}
