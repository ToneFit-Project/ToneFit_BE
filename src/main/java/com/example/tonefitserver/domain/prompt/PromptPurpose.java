package com.example.tonefitserver.domain.prompt;

/**
 * Prompt 용도 분류. v0.5 부터 INITIAL/FINAL/STRUCTURE 제거 → CORRECTION/GENERATION.
 * REQ-Reply 로 REPLY 추가 (V18 시드).
 *
 * <p>(purpose, recipient_type) 조합당 활성 prompt 1개. 총 활성 = 3 × 4 = 12.
 *
 * <p>REPLY 는 작성 단계 prompt 만 DB 관리(FUNC-Prp-04 — 수신자별 격식이 달라지는 단계).
 * 요약·파악·점검 보조 단계 prompt 는 수신자 무관이라 코드 상수로 둔다.
 */
public enum PromptPurpose {
    /** 교정 prompt — POST /corrections 가 사용. */
    CORRECTION,
    /** 생성 prompt — POST /generations 가 사용. */
    GENERATION,
    /** 회신 작성 prompt — POST /replies (작성 호출) 가 사용. */
    REPLY
}
