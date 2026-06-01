package com.example.tonefitserver.core.enums;

/**
 * 회원가입·서비스 진입 시 동의 받는 약관 종류. PM 요구사항 REQ-Agree FUNC-Ag-02.
 *
 * <p>5종, 그 중 3종 필수:
 * <ul>
 *   <li>{@link #SERVICE} — 서비스 이용약관 (필수, 미동의 시 진입 차단)</li>
 *   <li>{@link #PRIVACY} — 개인정보 처리방침 (필수, 미동의 시 진입 차단)</li>
 *   <li>{@link #ANALYTICS} — 행태정보 수집·이용 고지 (필수, 미동의 시 진입 차단)</li>
 *   <li>{@link #MARKETING} — 마케팅 수신 동의 (선택, 미동의 시 마케팅 발송만 제외)</li>
 *   <li>{@link #AI_LEARNING} — AI 학습 활용 동의 (선택, 미동의 시 학습 파이프라인에서만 제외)</li>
 * </ul>
 *
 * <p>각 항목의 currentVersion 은 약관 본문 변경이 있을 때 업데이트하여
 * (user_id, terms_type, version) 단위로 별도 row 가 적재되도록 한다.
 */
public enum TermsType {
    SERVICE("1.0", true),
    PRIVACY("1.0", true),
    ANALYTICS("1.0", true),
    MARKETING("1.0", false),
    AI_LEARNING("1.0", false);

    private final String currentVersion;
    private final boolean required;

    TermsType(String currentVersion, boolean required) {
        this.currentVersion = currentVersion;
        this.required = required;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public boolean isRequired() {
        return required;
    }
}
