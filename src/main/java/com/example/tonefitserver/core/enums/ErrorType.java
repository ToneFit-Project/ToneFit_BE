package com.example.tonefitserver.core.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식 오류"),
    TERMS_AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "TERMS_AGREEMENT_REQUIRED", "필수 약관에 동의해야 합니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "토큰 없음 또는 만료"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다."),
    INVALID_ID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_ID_TOKEN", "Google ID token 검증 실패"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한 없음"),
    USER_INACTIVE(HttpStatus.FORBIDDEN, "USER_INACTIVE", "비활성화된 계정입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "리소스 없음"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "허용되지 않은 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type 입니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 빈도가 너무 높습니다. 잠시 후 다시 시도해주세요."),
    // REQ-Limit FUNC-Lim-04: 계정 잠금이 아닌 일시 제한. IP 단위(RATE_LIMITED)와 구분되는 계정 단위 한도.
    USER_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "USER_RATE_LIMITED", "사용 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),
    // FUNC-Rep-14: "너무 김" 구분 응답 — 정리(인용·서명 제거) 후 대화 본문 합산 20,000자 초과 (PM 확정)
    CONTENT_TOO_LONG(HttpStatus.BAD_REQUEST, "CONTENT_TOO_LONG", "대화 내용이 너무 깁니다."),
    // FUNC-Rep-14: 회신 파악 단계 사전 점검 — 대화가 비었거나 본문을 읽을 수 없음
    EMPTY_THREAD(HttpStatus.BAD_REQUEST, "EMPTY_THREAD", "회신할 메일 대화를 읽을 수 없습니다."),
    // FUNC-Rep-14: 회신 파악 단계 사전 점검 — 메일 본문이 대부분 한국어가 아님
    NOT_KOREAN(HttpStatus.BAD_REQUEST, "NOT_KOREAN", "한국어 메일만 회신할 수 있습니다."),
    // FUNC-Lim-10: 전체 비용 보호 — 회신 기능만 수동 차단 (생성·교정은 유지)
    REPLY_SUSPENDED(HttpStatus.SERVICE_UNAVAILABLE, "REPLY_SUSPENDED", "회신 기능이 일시 중단되었습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류"),
    AI_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "AI_SERVICE_ERROR", "Gemini API 호출 실패");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
