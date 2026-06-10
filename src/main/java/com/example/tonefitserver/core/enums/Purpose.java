package com.example.tonefitserver.core.enums;

/**
 * 목적 유형. 교정·생성·프롬프트가 공유하는 도메인 enum 이라 중립 위치(core.enums)에 둔다.
 * (구 {@code domain.session.Purpose} — 교정 세션 제거(V17)와 함께 이전)
 */
public enum Purpose {
    REPORT,
    REQUEST,
    NOTICE,
    THANKS,
    APOLOGY,
    DECLINE,
    REPLY
}
