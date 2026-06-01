package com.example.tonefitserver.domain.session;

/**
 * 교정 세션 상태. v0.5 부터 흐름이 단순화돼 두 값만 사용.
 *
 * <ul>
 *   <li>{@link #IN_PROGRESS} — 교정 완료, 사용자 검토(개별 거부 선택) 중.</li>
 *   <li>{@link #CONFIRMED} — 송신 = 확정. 미처리 changes 는 일괄 ACCEPTED 로 기록.</li>
 * </ul>
 */
public enum Status {
    IN_PROGRESS,
    CONFIRMED
}
