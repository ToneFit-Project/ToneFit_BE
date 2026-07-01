package com.example.tonefitserver.core.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * AI primary(Gemini) 차단기 — 하드 실패(명시적 오류·타임아웃)가 짧은 시간에 쌓이면 한동안 fallback(GPT)으로
 * 직행시킨다 (PM 폴오버 (B) 차단기). 임시값: 1시간 5건 → 30분 OPEN → half-open 프로브.
 *
 * <p>상태:
 * <ul>
 *   <li>CLOSED — 정상. 헤지(primary 시도). 롤링 {@code window} 내 하드 실패가 {@code failureThreshold} 도달 → OPEN.</li>
 *   <li>OPEN — primary 차단(GPT 직행). {@code openMillis} 경과 후 다음 조회에서 HALF_OPEN 으로.</li>
 *   <li>HALF_OPEN — 프로브 허용(primary 시도). 성공 1건 → CLOSED, 실패 1건 → 다시 OPEN.</li>
 * </ul>
 *
 * <p>성공은 카운트를 줄이지 않는다(시간 창 eviction 으로만 감소) — "1시간 5건" 의도에 부합.
 * 기능별로 모델이 달라(생성 2.5 / 교정 3.5) 실패가 독립적이므로 인스턴스를 분리해 쓴다.
 *
 * <p>in-memory·단일 인스턴스 전제(UserRateLimiter 와 동일). 멀티 인스턴스 시 인스턴스별 차단기.
 * half-open 동안 동시 요청이 여럿이면 프로브가 소수가 아닐 수 있다 — 임시값 단계 허용, 정식화 시 제한.
 */
public final class AiCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long windowMillis;
    private final long openMillis;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private long openUntil = 0L;
    private final Deque<Long> failures = new ArrayDeque<>();

    public AiCircuitBreaker(int failureThreshold, long windowMillis, long openMillis, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.windowMillis = windowMillis;
        this.openMillis = openMillis;
        this.clock = clock;
    }

    /** primary(Gemini) 시도 허용 여부. OPEN 만 false(= GPT 직행). 만료된 OPEN 은 HALF_OPEN 으로 전이. */
    public synchronized boolean primaryAllowed() {
        if (state == State.OPEN && clock.getAsLong() >= openUntil) {
            state = State.HALF_OPEN;
        }
        return state != State.OPEN;
    }

    /** primary 하드 실패(에러·타임아웃) 기록. */
    public synchronized void recordPrimaryFailure() {
        long now = clock.getAsLong();
        if (state == State.HALF_OPEN) {
            trip(now);                 // 프로브 실패 → 다시 OPEN
            return;
        }
        if (state == State.OPEN) {
            return;                    // 이미 차단 중(원칙상 primary 미시도)
        }
        failures.addLast(now);
        evictOld(now);
        if (failures.size() >= failureThreshold) {
            trip(now);
        }
    }

    /** primary 성공 기록. half-open 프로브 성공이면 복귀. */
    public synchronized void recordPrimarySuccess() {
        if (state == State.HALF_OPEN) {
            close();
        }
        // CLOSED: 성공은 카운트에 영향 없음(시간 창 eviction 으로만 감소)
    }

    private void trip(long now) {
        state = State.OPEN;
        openUntil = now + openMillis;
        failures.clear();
    }

    private void close() {
        state = State.CLOSED;
        openUntil = 0L;
        failures.clear();
    }

    private void evictOld(long now) {
        while (!failures.isEmpty() && now - failures.peekFirst() > windowMillis) {
            failures.pollFirst();
        }
    }

    /** 테스트·관측용. */
    synchronized State state() {
        return state;
    }
}
