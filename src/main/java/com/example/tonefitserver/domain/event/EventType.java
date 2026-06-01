package com.example.tonefitserver.domain.event;

/**
 * BE 자동 발화 이벤트 종류. v0.5 부터 COMPLETED 제거 (finalize 단계 사라짐).
 * v0.52 명세 §7.1.
 *
 * <p>{@link #GENERATION_STARTED} 는 Phase 3 의 /generations 성공 시 발화.
 */
public enum EventType {
    STARTED("CORRECTION_STARTED"),
    COPIED("CORRECTION_COPIED"),
    REJECTED("REJECTION_CLICKED"),
    GENERATION_STARTED("GENERATION_STARTED");

    private final String amplitudeName;

    EventType(String amplitudeName) {
        this.amplitudeName = amplitudeName;
    }

    /** Amplitude UI에 노출되는 이벤트명. DB enum은 짧은 이름을 쓰고 외부 분석에는 풀 네임을 보낸다. */
    public String amplitudeName() {
        return amplitudeName;
    }
}
