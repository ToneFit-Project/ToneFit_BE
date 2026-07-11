package com.example.tonefitserver.core.security;

import com.example.tonefitserver.core.enums.ErrorType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
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
 * <p>대상 (모두 client IP 당 분당 {@code ratelimit.ip.per-minute} 회):
 * <ul>
 *   <li>POST /api/v1/auth/google — Google OAuth 호출 보호</li>
 *   <li>POST /api/v1/corrections — Gemini 호출 비용·쿼터 보호</li>
 *   <li>POST /api/v1/generations — Gemini 호출 보호 + 웹 데모 public 호출의 1차 남용 방어</li>
 * </ul>
 *
 * <p>한도 값은 {@link RateLimitProperties}(환경변수)로 주입 — 재배포 없이 조정.
 * <p>키: client IP — {@code CloudFront-Viewer-Address} 헤더 기준 (origin request policy 에 포함,
 * viewer 가 같은 이름을 보내와도 CloudFront 가 덮어써 위조 불가). X-Forwarded-For 는 첫 값이
 * 클라이언트 조작 가능해 폐기 — fallback 시에도 CloudFront 가 append 한 최우측 값만 신뢰.
 * (SG 가 CloudFront prefix list 로 제한되어 직결 우회 경로 없음, 2026-07)
 * <p>저장소: Caffeine LRU + TTL eviction. 최대 100k 키 / 10분 미사용 시 만료.
 * <p>알고리즘: Bucket4j 토큰 버킷 + <b>intervally refill</b> — 1분마다 capacity 를 일괄 보충(중간 보충 없음)
 *    하므로 "1분 윈도우 내 N회 초과 차단"으로 동작한다. (greedy 보충이면 초기 버스트 + 연속 보충으로
 *    윈도우 내 최대 ~2N회까지 통과되어 QA 가 혼란스러움 — intervally 로 직관적인 분당 N회를 보장.)
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final List<RateLimitRule> rules;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    public RateLimitFilter(RateLimitProperties properties) {
        int perMinute = properties.perMinute();
        this.rules = List.of(
                new RateLimitRule("POST", "/api/v1/auth/google", perMinute, WINDOW),
                // refresh 무차별 대입(토큰 추측·재사용 스프레이) 방어 — 정상 FE 는 시간당 1회 수준.
                new RateLimitRule("POST", "/api/v1/auth/refresh", perMinute, WINDOW),
                new RateLimitRule("POST", "/api/v1/corrections", perMinute, WINDOW),
                new RateLimitRule("POST", "/api/v1/generations", perMinute, WINDOW),
                // 회신 — 인증 필수 경로라 계정 한도(분당 3회)가 1차 방어. IP 룰은 무인증 스프레이 등 2차 방어.
                new RateLimitRule("POST", "/api/v1/replies", perMinute, WINDOW),
                new RateLimitRule("POST", "/api/v1/replies/analysis", perMinute, WINDOW)
        );
    }

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
        for (RateLimitRule r : rules) {
            if (r.matches(req)) return r;
        }
        return null;
    }

    private String clientIp(HttpServletRequest req) {
        return resolveClientIp(
                req.getHeader("CloudFront-Viewer-Address"),
                req.getHeader("X-Forwarded-For"),
                req.getRemoteAddr());
    }

    /**
     * 신뢰 순서: ① CloudFront-Viewer-Address("IP:port") — 정책에 포함돼 CloudFront 가 항상
     * viewer 값으로 덮어쓰므로 위조 불가 ② XFF 최우측 — CloudFront 가 append 한 값(왼쪽은 전부
     * 클라이언트 조작 가능이라 첫 값 사용은 금지) ③ remoteAddr.
     */
    static String resolveClientIp(String viewerAddress, String xff, String remoteAddr) {
        if (StringUtils.hasText(viewerAddress)) {
            String ip = stripPort(viewerAddress.trim());
            if (StringUtils.hasText(ip)) return ip;
        }
        if (StringUtils.hasText(xff)) {
            int comma = xff.lastIndexOf(',');
            return (comma >= 0 ? xff.substring(comma + 1) : xff).trim();
        }
        return remoteAddr;
    }

    /**
     * "IP:port" → IP. CloudFront-Viewer-Address 는 항상 포트를 포함한다(AWS 명세) —
     * IPv4 "1.2.3.4:5678", IPv6 는 무괄호 "2001:db8::1:5678" 로 오므로 마지막 콜론 뒤가
     * 전부 숫자면 포트로 보고 떼어낸다. "[IPv6]:port" 괄호 형식도 방어적으로 처리.
     */
    static String stripPort(String address) {
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            if (end > 0) return address.substring(1, end);
        }
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon < address.length() - 1
                && address.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            return address.substring(0, colon);
        }
        return address;
    }

    /** intervally refill: period 마다 capacity 일괄 보충. 중간 보충 없음 → 윈도우 내 capacity 초과 차단. */
    private Bucket newBucket(RateLimitRule rule) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(rule.capacity(), Refill.intervally(rule.capacity(), rule.period())))
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
