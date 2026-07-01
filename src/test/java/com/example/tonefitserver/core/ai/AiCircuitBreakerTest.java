package com.example.tonefitserver.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiCircuitBreaker 단위 테스트 — 주입 clock 으로 결정적. Spring/Docker 불필요.
 */
class AiCircuitBreakerTest {

    private static final long WINDOW = 3_600_000L;  // 1h
    private static final long OPEN = 1_800_000L;    // 30m

    private final AtomicLong now = new AtomicLong(0L);

    private AiCircuitBreaker breaker() {
        return new AiCircuitBreaker(5, WINDOW, OPEN, now::get);
    }

    @Test
    @DisplayName("임계 미만은 CLOSED 유지, 임계(5건) 도달 시 OPEN(primary 차단)")
    void opensAtThreshold() {
        AiCircuitBreaker cb = breaker();
        for (int i = 0; i < 4; i++) cb.recordPrimaryFailure();
        assertThat(cb.primaryAllowed()).isTrue();
        cb.recordPrimaryFailure();
        assertThat(cb.primaryAllowed()).isFalse();
    }

    @Test
    @DisplayName("OPEN 은 openMillis 경과 후 HALF_OPEN 으로 전이(primary 재허용)")
    void halfOpenAfterCooldown() {
        AiCircuitBreaker cb = breaker();
        for (int i = 0; i < 5; i++) cb.recordPrimaryFailure();
        assertThat(cb.primaryAllowed()).isFalse();
        now.addAndGet(OPEN);
        assertThat(cb.primaryAllowed()).isTrue();
        assertThat(cb.state()).isEqualTo(AiCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("half-open 프로브 성공 → CLOSED 복귀")
    void halfOpenSuccessCloses() {
        AiCircuitBreaker cb = breaker();
        for (int i = 0; i < 5; i++) cb.recordPrimaryFailure();
        now.addAndGet(OPEN + 1);
        cb.primaryAllowed();   // 전이 트리거
        cb.recordPrimarySuccess();
        assertThat(cb.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
        assertThat(cb.primaryAllowed()).isTrue();
    }

    @Test
    @DisplayName("half-open 프로브 실패 → 다시 OPEN")
    void halfOpenFailureReopens() {
        AiCircuitBreaker cb = breaker();
        for (int i = 0; i < 5; i++) cb.recordPrimaryFailure();
        now.addAndGet(OPEN + 1);
        cb.primaryAllowed();   // HALF_OPEN
        cb.recordPrimaryFailure();
        assertThat(cb.primaryAllowed()).isFalse();
    }

    @Test
    @DisplayName("window 밖 오래된 실패는 만료되어 카운트되지 않음")
    void evictsOldFailures() {
        AiCircuitBreaker cb = breaker();
        for (int i = 0; i < 4; i++) cb.recordPrimaryFailure();  // t=0 에 4건
        now.addAndGet(WINDOW + 1);                              // 1h+ 경과 → 4건 만료
        cb.recordPrimaryFailure();                              // 새 1건
        assertThat(cb.primaryAllowed()).isTrue();              // 유효 1건 → CLOSED
    }
}
