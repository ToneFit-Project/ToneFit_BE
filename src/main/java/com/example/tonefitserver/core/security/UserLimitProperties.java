package com.example.tonefitserver.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사용자별(계정 단위) AI 호출 한도. PM 요구사항 REQ-Limit FUNC-Lim-03.
 *
 * <p>값은 서버 설정(application.yml + 환경변수, 운영은 AWS Secrets Manager 주입)으로 관리하여
 * Extension 재배포 없이 조정 가능. 현재 값(daily 100 / per-minute 10)은 임시 — FUNC-Lim-07 확정 후 교체.
 *
 * <p>적용: 정식(로그인) 사용자만. 익명(데모, REQ-Demo)은 미적용. correction/generation 각각 독립 카운트.
 */
@ConfigurationProperties(prefix = "limit.user")
public record UserLimitProperties(
        int daily,
        int perMinute
) {
}
