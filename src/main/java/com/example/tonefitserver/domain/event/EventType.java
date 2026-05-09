package com.example.tonefitserver.domain.event;

public enum EventType {
    STARTED("CORRECTION_STARTED"),
    COMPLETED("CORRECTION_COMPLETED"),
    COPIED("CORRECTION_COPIED"),
    REJECTED("REJECTION_CLICKED");

    private final String amplitudeName;

    EventType(String amplitudeName) {
        this.amplitudeName = amplitudeName;
    }

    /** Amplitude UI에 노출되는 이벤트명. DB enum은 짧은 이름을 쓰고 외부 분석에는 풀 네임을 보낸다. */
    public String amplitudeName() {
        return amplitudeName;
    }
}
