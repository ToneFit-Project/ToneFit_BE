package com.example.tonefitserver.core.dto.user;

import com.example.tonefitserver.core.enums.Plan;

import java.time.LocalDateTime;

/**
 * GET /users/me 응답. v0.5 + PM 후속 결정 반영.
 *
 * <p>nickname: Google 프로필 표시 이름 (정식 user 만, 익명은 null).
 * provider: 정식 user 만, 익명은 null.
 * free_used 는 BE 미관리 → 응답에서도 제거 (FE/localStorage).
 */
public record UserResponse(
        Long userId,
        boolean isGuest,
        String email,
        String nickname,
        String provider,
        Plan plan,
        int creditBalance,
        LocalDateTime createdAt
) {
}
