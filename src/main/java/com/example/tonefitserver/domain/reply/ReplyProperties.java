package com.example.tonefitserver.domain.reply;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 회신 시간 예산 (FUNC-Rep-12 — 60초 목표 중 ② 작성+점검 구간).
 * ① 파악 구간은 단일 호출이라 Gemini RestClient read timeout(30초)으로 충분.
 *
 * <p>예산 안에서 작성 → 점검 → (실패 시) 1회 재작성을 돌리고,
 * 시간이 모자라면 재작성하지 않고 처음 초안을 그대로 반환한다.
 */
@ConfigurationProperties(prefix = "reply")
public record ReplyProperties(int draftBudgetSeconds) {

    public long draftBudgetMillis() {
        return draftBudgetSeconds * 1_000L;
    }
}
