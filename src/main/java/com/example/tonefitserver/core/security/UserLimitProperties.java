package com.example.tonefitserver.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사용자별(계정 단위) AI 호출 한도. PM 요구사항 REQ-Limit FUNC-Lim-03/08/09.
 *
 * <p>값은 서버 설정(application.yml + 환경변수, 운영은 AWS Secrets Manager 주입)으로 관리하여
 * Extension 재배포 없이 조정 가능. 현재 값은 임시 — FUNC-Lim-07 확정 후 교체.
 *
 * <ul>
 *   <li>{@code daily} — 하루 한도. <b>생성·교정·회신 3기능 합산</b> (FUNC-Lim-08, PM 확정)</li>
 *   <li>{@code perMinute} — 분당 한도 (교정·생성, 카테고리별 독립)</li>
 *   <li>{@code replyPerMinute} — 회신 분당 한도. 1회 비용·시간이 커서 따로 더 낮게 (FUNC-Lim-09, PM 확정 3회)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "limit.user")
public record UserLimitProperties(
        int daily,
        int perMinute,
        int replyPerMinute
) {
}
