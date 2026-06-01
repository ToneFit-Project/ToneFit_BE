package com.example.tonefitserver.domain.prompt;

/**
 * Prompt 용도 분류. v0.5 부터 INITIAL/FINAL/STRUCTURE 제거 → CORRECTION/GENERATION.
 *
 * <p>(purpose, recipient_type) 조합당 활성 prompt 1개. 총 활성 = 2 × 4 = 8.
 */
public enum PromptPurpose {
    /** 교정 prompt — POST /corrections 가 사용. */
    CORRECTION,
    /** 생성 prompt — POST /generations 가 사용. */
    GENERATION
}
