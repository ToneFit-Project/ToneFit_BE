package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * refresh_token 쿠키 발급/추출 헬퍼.
 *
 * <p>속성:
 * <ul>
 *   <li>Name: {@value #NAME}</li>
 *   <li>HttpOnly: true / Secure: true (운영) — XSS 노출 차단</li>
 *   <li>SameSite=None — FE 가 별도 도메인(tonefit.kr)·Extension 에서 호출, credentials: include 와 함께 송신</li>
 *   <li>Path: /api/v1/auth — refresh/logout 에만 자동 송신, 다른 API 요청에 불필요한 송신 방지</li>
 *   <li>Max-Age: 익명 7일 / 정식 30일 (PM 요구사항 FUNC-Co-07/08, JWT 만료와 일치)</li>
 * </ul>
 * 로컬 dev 환경(HTTP)에선 Secure=false 가 필요해서 {@code app.cookie.secure} 로 토글.
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "refresh_token";
    private static final String PATH = "/api/v1/auth";

    /** HTTPS 전용 여부. 운영 true / 로컬 dev false. application-*.yml 에서 분기. */
    private final boolean secure;

    public RefreshTokenCookie(@Value("${app.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    /**
     * SameSite 속성. 브라우저 정책상 {@code SameSite=None} 은 {@code Secure} 가 필수이며,
     * Secure 없는 None 쿠키는 브라우저가 저장을 거부한다. 따라서 secure=false(로컬 dev)에선 Lax 로 내린다.
     * 로컬은 FE/BE 가 같은 localhost(포트만 다름 = same-site)라 Lax 로도 쿠키가 정상 송신된다.
     */
    private String sameSite() {
        return secure ? "None" : "Lax";
    }

    /**
     * 발급(또는 갱신) 시 사용할 Set-Cookie 헤더 값.
     * Max-Age 는 토큰 자체 만료와 동기화 — JwtTokenProvider 가 정한 분기 값을 그대로 사용.
     */
    public String issueHeader(String refreshToken, boolean isGuest) {
        long maxAgeSeconds = JwtTokenProvider.refreshValidityMillis(isGuest) / 1000;
        return ResponseCookie.from(NAME, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite())
                .path(PATH)
                .maxAge(maxAgeSeconds)
                .build()
                .toString();
    }

    /** 로그아웃 시 즉시 만료시킬 Set-Cookie 헤더 값 (Max-Age=0, 빈 값). */
    public String expireHeader() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite())
                .path(PATH)
                .maxAge(0)
                .build()
                .toString();
    }

    /** Set-Cookie 헤더 이름 (편의). */
    public String headerName() {
        return HttpHeaders.SET_COOKIE;
    }

    /** 요청에서 refresh_token 쿠키 값 추출. 없으면 null. */
    public String extract(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
