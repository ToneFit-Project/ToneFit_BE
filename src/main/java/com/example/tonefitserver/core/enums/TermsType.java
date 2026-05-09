package com.example.tonefitserver.core.enums;

/**
 * 회원가입 시 동의 받는 약관 종류.
 *
 * <p>SERVICE / PRIVACY / CONTENT_USAGE / ANALYTICS 는 가입 필수, MARKETING 은 선택.
 *
 * <p>각 항목의 currentVersion 은 약관 본문 변경이 있을 때 업데이트하여
 * (user_id, terms_type, version) 단위로 별도 row 가 적재되도록 한다.
 */
public enum TermsType {
    /** 서비스 이용약관 */
    SERVICE("1.0", true),
    /** 개인정보 처리방침 */
    PRIVACY("1.0", true),
    /** 원문 저장·학습 활용 동의 */
    CONTENT_USAGE("1.0", true),
    /** 행태정보 수집·이용 고지 */
    ANALYTICS("1.0", true),
    /** 마케팅 수신 동의 (선택) */
    MARKETING("1.0", false);

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
