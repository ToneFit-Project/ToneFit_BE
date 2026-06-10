package com.example.tonefitserver.core.enums;

/**
 * 수신자 유형. 교정·생성·프롬프트가 공유하는 도메인 enum 이라 중립 위치(core.enums)에 둔다.
 * (구 {@code domain.session.Receiver} — 교정 세션 제거(V17)와 함께 이전)
 */
public enum Receiver {
    DIRECT_SUPERVISOR,
    OTHER_DEPT_COLLEAGUE,
    EXTERNAL_PARTNER,
    CLIENT
}
