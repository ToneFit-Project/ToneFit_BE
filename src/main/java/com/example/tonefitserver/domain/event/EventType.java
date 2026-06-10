package com.example.tonefitserver.domain.event;

/**
 * BE 자동 발화 이벤트 종류 — "BE API 를 거치는" 동작만 BE 가 Amplitude 로 발화한다 (FUNC-Amp-03).
 *
 * <p>v0.6 부터 교정 흐름은 {@link #STARTED}(교정 요청·성공) 1종만 남는다. 항목 복사(COPIED)·
 * 거부 클릭(REJECTED)은 API 를 거치지 않는 클라이언트 동작이라 FE 가 직접 측정한다(교정 세션 제거와 함께 폐지).
 *
 * <p>{@link #GENERATION_STARTED} 는 /generations 성공 시 발화(정식 사용자만).
 */
public enum EventType {
    STARTED("CORRECTION_STARTED"),
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
