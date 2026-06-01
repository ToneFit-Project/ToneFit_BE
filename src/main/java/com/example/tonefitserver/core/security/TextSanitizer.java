package com.example.tonefitserver.core.security;

import java.util.regex.Pattern;

/**
 * 사용자 입력 텍스트에서 XSS 위험 패턴만 선택적으로 제거 + 줄바꿈 정규화 + null byte 제거.
 *
 * <p>대상: 사용자 입력으로부터 DB 에 저장 후 history 응답에 그대로 노출되는 텍스트 필드.
 * <ul>
 *   <li>CorrectionRequest: originalEmail</li>
 *   <li>ConfirmRequest: userFinal</li>
 *   <li>RejectRequest: reasonText</li>
 *   <li>GenerationRequest: briefContent (Phase 3)</li>
 * </ul>
 *
 * <p>전제: 한국어 비즈니스 이메일은 평문 가정. 다만 본문에 {@code <3}, {@code <email@x.com>},
 * {@code 1 < 2 > 3} 같은 우연한 angle bracket 사용은 보존되어야 함.
 *
 * <p>정책:
 * <ul>
 *   <li>위험 태그·속성·URL 스킴만 선택 차단 (whitelist 기반). 알려지지 않은 태그는 텍스트로 보존.</li>
 *   <li>줄바꿈 통일: CRLF, 단독 CR 모두 LF 로 정규화.</li>
 *   <li>Null byte (U+0000) 제거: PostgreSQL TEXT 가 못 저장하여 INSERT 실패 → 500 방지.</li>
 *   <li>그 외 제어 문자(U+0001~U+001F)는 export 시점에서 별도 처리 (CSV/Excel 라이브러리가 자체 escape).</li>
 * </ul>
 *
 * <p>본 helper 는 storage-time defense-in-depth 이며, FE 도 user-generated content 렌더링 시
 * textContent 또는 프레임워크 escape (React {value}, Vue {{value}} 등) 사용 권장.
 */
public final class TextSanitizer {

    /**
     * 내용까지 통째 제거되는 블록 태그 — script, iframe, style, object.
     * 여는 태그부터 닫는 태그까지 (case-insensitive, dotall).
     */
    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
            "(?is)<(script|iframe|style|object)\\b[^>]*>.*?</\\1\\s*>");

    /**
     * 단독으로 출현해도 위험한 태그 — 위 4종 + embed/form/meta/link/svg/math/img/video/audio/source/frame/applet/base.
     * 여는·닫는·자가종결 모두 제거.
     */
    private static final Pattern DANGEROUS_TAG = Pattern.compile(
            "(?i)</?(script|iframe|object|embed|form|meta|link|style|svg|math|img|video|audio|source|frame|frameset|applet|base)\\b[^>]*/?>");

    /** 어느 태그에든 붙은 이벤트 핸들러 속성 (onclick, onerror, onload …). 속성만 제거. */
    private static final Pattern EVENT_HANDLER = Pattern.compile(
            "(?i)\\son[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)");

    /** javascript: URL 스킴. <a href="javascript:..."> 같은 케이스 차단. */
    private static final Pattern JAVASCRIPT_URL = Pattern.compile(
            "(?i)\\bjavascript\\s*:[^\\s'\"<>]*");

    /** 줄바꿈 정규화: CRLF 또는 단독 CR 을 모두 LF 로 통일. */
    private static final Pattern LINE_ENDING = Pattern.compile("\\r\\n?");

    /**
     * Null byte 상수. 소스에 literal NUL 을 넣으면 파일이 binary 로 인식되므로 char 캐스팅으로 생성.
     * (Java 의 Unicode escape preprocessor 는 주석 안에서도 동작하므로 'backslash u 0000' 도 사용 불가)
     */
    private static final String NULL_BYTE = String.valueOf((char) 0);

    private TextSanitizer() {}

    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = DANGEROUS_BLOCK.matcher(input).replaceAll("");
        s = DANGEROUS_TAG.matcher(s).replaceAll("");
        s = EVENT_HANDLER.matcher(s).replaceAll("");
        s = JAVASCRIPT_URL.matcher(s).replaceAll("");
        s = LINE_ENDING.matcher(s).replaceAll("\n");
        s = s.replace(NULL_BYTE, "");
        return s;
    }
}
