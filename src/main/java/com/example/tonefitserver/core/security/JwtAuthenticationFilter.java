package com.example.tonefitserver.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Authorization: Bearer <jwt> 헤더를 검증하고 SecurityContext 에 인증 정보를 채운다.
 *
 * <p>principal 로는 user.id (Long) 을 설정한다.
 * 컨트롤러에서는 {@code @AuthenticationPrincipal Long userId} 로 받는다.
 *
 * <p><b>무효 Bearer 즉시 401</b>: 헤더에 Bearer 를 실었는데 무효(만료·위조·타입 불일치)면
 * permitAll 경로(생성 데모)여도 401 로 거절한다 — 로그인 사용자의 생성 호출이 데모로 조용히
 * 강등되어 만료가 은폐되고(FE 가 401 을 못 받아 갱신 트리거 불가) 계정 한도를 우회하는 것 방지.
 * 헤더가 아예 없으면 기존대로 익명 통과(웹 데모 경로). {@code /api/v1/auth/**} 는 예외 —
 * stale 토큰을 실은 재로그인 요청이 막히는 데드락 방지.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 무효 Bearer 401 제외 경로 — 재로그인 자체가 막히는 데드락 방지. */
    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null) {
            boolean validAccess = jwtTokenProvider.validateToken(token)
                    && JwtTokenProvider.TYPE_ACCESS.equals(jwtTokenProvider.getType(token));
            // access token 만 인증 컨텍스트로 인정. refresh token 으로 API 직접 호출 차단.
            // type claim 없는 옛 토큰도 거부 — 재로그인 필요.
            if (validAccess) {
                Long userId = jwtTokenProvider.getUserId(token);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else if (!request.getRequestURI().startsWith(AUTH_PATH_PREFIX)) {
                writeUnauthorized(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /** SecurityConfig 의 unauthorizedEntryPoint 와 동일 형식 — FE 는 401 을 한 가지 모양으로만 본다. */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(
                "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"토큰 없음 또는 만료\"}}"
                        .getBytes(StandardCharsets.UTF_8));
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
