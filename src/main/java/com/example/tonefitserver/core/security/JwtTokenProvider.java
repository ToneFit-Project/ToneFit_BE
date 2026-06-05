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
 * <p>토큰 구조: subject = user.id, claim {@code type=access}.
 * 익명·refresh 폐지로 access token 만 발급한다.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";

    @Value("${jwt.secret}")
    private String secretKeyString;

    @Value("${jwt.expiration}")
    private long accessValidityInMilliseconds;

    private SecretKey key;

    @PostConstruct
    protected void init() {
        this.key = Keys.hmacShaKeyFor(secretKeyString.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId) {
        return createToken(String.valueOf(userId), accessValidityInMilliseconds,
                Map.of(CLAIM_TYPE, TYPE_ACCESS));
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

    /** 토큰 종류 — access. claim 없으면 null. */
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
