package com.example.tonefitserver.core.security;

import com.example.tonefitserver.core.enums.ErrorType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 단순 in-memory IP 기반 rate limit. 단일 인스턴스 가정.
 * 분산 환경 가면 Redis 기반(Bucket4j ProxyManager) 으로 교체 필요.
 *
 * <p>대상 (v0.52 API 명세 §IP Rate Limit):
 * <ul>
 *   <li>POST /api/v1/auth/anonymous — 익명 사용자 무한 생성 방지 (FUNC-NON-02)</li>
 *   <li>POST /api/v1/auth/google — Google OAuth 호출 보호</li>
 *   <li>POST /api/v1/corrections — Gemini 호출 비용·쿼터 보호</li>
 *   <li>POST /api/v1/generations — Gemini 호출 비용·쿼터 보호</li>
 * </ul>
 * 모두 분당 10회 / client IP.
 *
 * <p>키: client IP (X-Forwarded-For 우선 — ALB 뒤에 있으므로. 없으면 remoteAddr).
 * <p>저장소: Caffeine LRU + TTL eviction (메모리 누수 방지). 최대 100k 키 / 10분 미사용 시 만료.
 * <p>알고리즘: Bucket4j 토큰 버킷. 짧은 burst 허용하되 지속 분당 N회 유지.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final List<RateLimitRule> RULES = List.of(
            new RateLimitRule("POST", "/api/v1/auth/anonymous", 10, Duration.ofMinutes(1)),
            new RateLimitRule("POST", "/api/v1/auth/google", 10, Duration.ofMinutes(1)),
            new RateLimitRule("POST", "/api/v1/corrections", 10, Duration.ofMinutes(1)),
            new RateLimitRule("POST", "/api/v1/generations", 10, Duration.ofMinutes(1))
    );

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitRule rule = matchRule(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientIp(request) + ":" + rule.id();
        Bucket bucket = buckets.get(key, k -> newBucket(rule));

        if (!bucket.tryConsume(1)) {
            writeRateLimitResponse(response, rule);
            return;
        }
        chain.doFilter(request, response);
    }

    private RateLimitRule matchRule(HttpServletRequest req) {
        for (RateLimitRule r : RULES) {
            if (r.matches(req)) return r;
        }
        return null;
    }

    /** ALB 뒤이므로 X-Forwarded-For 가 우선. 콤마 구분 리스트면 첫 번째(원본 클라). */
    private String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(fwd)) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return req.getRemoteAddr();
    }

    private Bucket newBucket(RateLimitRule rule) {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(rule.capacity(), rule.period()))
                .build();
    }

    private void writeRateLimitResponse(HttpServletResponse res, RateLimitRule rule) throws IOException {
        res.setStatus(ErrorType.RATE_LIMITED.getStatus().value());
        res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Retry-After", String.valueOf(rule.period().toSeconds()));
        res.getOutputStream().write(
                ("{\"success\":false,\"error\":{\"code\":\"" + ErrorType.RATE_LIMITED.getCode()
                        + "\",\"message\":\"" + ErrorType.RATE_LIMITED.getMessage() + "\"}}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private record RateLimitRule(String method, String path, int capacity, Duration period) {
        boolean matches(HttpServletRequest req) {
            if (!method.equals(req.getMethod())) return false;
            // 와일드카드(* 또는 **) 포함된 경로는 AntPathMatcher 로, 아니면 정확 일치
            if (path.contains("*")) {
                return PATH_MATCHER.match(path, req.getRequestURI());
            }
            return path.equals(req.getRequestURI());
        }

        String id() {
            return method + ":" + path;
        }
    }
}
