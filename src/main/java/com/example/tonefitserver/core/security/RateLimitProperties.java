package com.example.tonefitserver.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IP 단위 요청 빈도 제한 설정. 환경변수(운영은 AWS Secrets Manager)로 조정 — 재배포 없이 QA·튜닝 가능.
 *
 * <p>{@code perMinute}: client IP 당 분당 허용 횟수. 적용 엔드포인트는 RateLimitFilter 의 룰 목록 참조.
 */
@ConfigurationProperties(prefix = "ratelimit.ip")
public record RateLimitProperties(
        int perMinute
) {
}
