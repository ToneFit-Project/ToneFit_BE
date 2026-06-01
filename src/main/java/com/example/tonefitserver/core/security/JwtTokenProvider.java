package com.example.tonefitserver.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 발급/검증.
 *
 * <p>토큰 구조:
 * <ul>
 *   <li>subject = user.id (정식/익명 무관, 문자열로 직렬화)</li>
 *   <li>claim "is_guest" = 익명 사용자 여부 boolean</li>
 * </ul>
 * 이렇게 통일하면 정식과 익명을 같은 인증 흐름에서 처리할 수 있고,
 * principal_id 가 익명·가입 동일 키라는 PRD 정의와도 정합한다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String CLAIM_IS_GUEST = "is_guest";
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    @Value("${jwt.secret}")
    private String secretKeyString;

    @Value("${jwt.expiration}")
    private long accessValidityInMilliseconds;

    /** Refresh token 만료. PM 요구사항 FUNC-Co-07/08: 익명 7일 / 정식 30일. */
    private static final long REFRESH_GUEST_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final long REFRESH_USER_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private SecretKey key;

    @PostConstruct
    protected void init() {
        this.key = Keys.hmacShaKeyFor(secretKeyString.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, boolean isGuest) {
        return createToken(String.valueOf(userId), accessValidityInMilliseconds,
                Map.of(CLAIM_IS_GUEST, isGuest, CLAIM_TYPE, TYPE_ACCESS));
    }

    public String createRefreshToken(Long userId, boolean isGuest) {
        return createToken(String.valueOf(userId), refreshValidityMillis(isGuest),
                Map.of(CLAIM_IS_GUEST, isGuest, CLAIM_TYPE, TYPE_REFRESH));
    }

    /** refresh token 만료 — 익명/정식 분기. cookie Max-Age 도 같은 값을 써야 한다. */
    public static long refreshValidityMillis(boolean isGuest) {
        return isGuest ? REFRESH_GUEST_MILLIS : REFRESH_USER_MILLIS;
    }

    private String createToken(String subject, long validityInMilliseconds, Map<String, Object> claims) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + validityInMilliseconds);

        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(now)
                .expiration(validity)
                .signWith(key)
                .compact();
    }

    public Long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public boolean getIsGuest(String token) {
        Object v = getClaims(token).get(CLAIM_IS_GUEST);
        return v instanceof Boolean b && b;
    }

    /** 토큰 종류 — access / refresh. claim 없으면 null. */
    public String getType(String token) {
        Object v = getClaims(token).get(CLAIM_TYPE);
        return v == null ? null : v.toString();
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
