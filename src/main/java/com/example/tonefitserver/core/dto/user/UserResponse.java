package com.example.tonefitserver.core.dto.user;

import com.example.tonefitserver.core.enums.CareerLevel;
import com.example.tonefitserver.core.enums.Industry;
import com.example.tonefitserver.core.enums.Plan;

import java.time.LocalDateTime;

public record UserResponse(
        Long userId,
        boolean isGuest,
        String email,
        String nickname,
        Industry industry,
        CareerLevel careerLevel,
        Plan plan,
        int freeUsed,
        int creditBalance,
        LocalDateTime createdAt
) {
}
