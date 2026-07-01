package com.example.tonefitserver.core.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 폴오버(헤지 + 차단기) 설정. 임계값은 #59 지연 데이터로 확정 예정 — 우선 PM 협의 임시값.
 *
 * @param enabled       폴오버 활성 스위치 (동일 키를 @ConditionalOnProperty 도 참조)
 * @param hedgeDelayMs  primary(Gemini) 응답이 이만큼 없으면 fallback(GPT) 병행 시작 (즉시 실패는 즉시)
 * @param deadlineMs    전체 데드라인 — 초과 시 실패
 * @param circuit       차단기 임계
 */
@ConfigurationProperties(prefix = "ai.failover")
public record FailoverProperties(
        boolean enabled,
        long hedgeDelayMs,
        long deadlineMs,
        Circuit circuit
) {
    /**
     * @param failureThreshold window 내 하드 실패 이 수 도달 시 OPEN
     * @param windowMs         하드 실패 카운트 롤링 창
     * @param openMs           OPEN 유지 시간(이후 half-open)
     */
    public record Circuit(int failureThreshold, long windowMs, long openMs) {
    }
}
