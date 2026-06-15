package com.example.tonefitserver.core.dto.user;

import com.example.tonefitserver.core.enums.Plan;

import java.time.LocalDateTime;

/**
 * GET /users/me 응답.
 *
 * <p>nickname: Google 프로필 표시 이름.
 * marketingAgreed / aiLearningAgreed: 선택 약관 2건의 활성 동의 여부 — FE 가 홈·프로필 화면에서
 * 한 번에 받도록 통합 (전체 6종 현황은 GET /users/me/terms). MAIL_READ 는 회신 게이트라 미포함.
 * 프로필 이미지는 여기 없다 — 토큰 생애주기와 동일하므로 /auth/google 응답으로 받아 FE 가 캐시.
 * free_used 는 BE 미관리 → 응답 제외 (FE/localStorage).
 */
public record UserResponse(
        Long userId,
        String email,
        String nickname,
        String provider,
        Plan plan,
        int creditBalance,
        boolean marketingAgreed,
        boolean aiLearningAgreed,
        LocalDateTime createdAt
) {
}
