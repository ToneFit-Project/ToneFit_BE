package com.example.tonefitserver.domain.session;

public enum Status {
    DRAFT,
    IN_PROGRESS,
    /** 재교정 AI 호출 진행 중. 동시 진입 차단용 in-flight 마커. */
    RECORRECTING,
    /** 최종 다듬기 AI 호출 진행 중. 동시 진입 차단용 in-flight 마커. */
    FINALIZING,
    EDITING,
    CONFIRMED
}
