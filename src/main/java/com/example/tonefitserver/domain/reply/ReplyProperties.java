package com.example.tonefitserver.domain.reply;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회신 운영 설정.
 *
 * <ul>
 *   <li>{@code enabled} — FUNC-Lim-10 수동 킬스위치. 전체 비용이 위험선에 가까워지면 false 로 내려
 *       회신만 차단(생성·교정 유지, {@code 503 REPLY_SUSPENDED}). 위험선이 데이터 기반으로 정의될
 *       때까지 수동 운영(PM 확정) — 정의 후 모니터링·경보와 연계 예정.</li>
 *   <li>{@code draftBudgetSeconds} — FUNC-Rep-12 시간 예산. 60초 목표 중 ② 작성+점검 구간.
 *       ① 파악 구간은 단일 호출이라 Gemini RestClient read timeout(30초)으로 충분.
 *       예산 안에서 작성 → 점검 → (실패 시) 1회 재작성을 돌리고, 시간이 모자라면
 *       재작성하지 않고 처음 초안을 그대로 반환한다.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "reply")
public record ReplyProperties(boolean enabled, int draftBudgetSeconds) {

    public long draftBudgetMillis() {
        return draftBudgetSeconds * 1_000L;
    }
}
