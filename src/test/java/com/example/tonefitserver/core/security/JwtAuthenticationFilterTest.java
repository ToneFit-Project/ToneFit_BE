package com.example.tonefitserver.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter 단위 테스트 — 무효 Bearer 즉시 401 정책(데모 강등 방지)과
 * 예외 경로(/auth/**, 헤더 없음)를 검증한다.
 */
class JwtAuthenticationFilterTest {

    private JwtTokenProvider provider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        provider = mock(JwtTokenProvider.class);
        filter = new JwtAuthenticationFilter(provider);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String uri, String bearer) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        return req;
    }

    @Test
    @DisplayName("무효 토큰 + permitAll 경로(생성) → 401 즉시 반환, 체인 미진행")
    void invalidTokenOnPermitAllPathReturns401() throws Exception {
        when(provider.validateToken("expired")).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/generations", "expired"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"UNAUTHORIZED\"");
        assertThat(chain.getRequest()).isNull();   // 체인 미진행 — 데모 강등 없음
    }

    @Test
    @DisplayName("무효 토큰이라도 /api/v1/auth/** 는 통과 — 재로그인 데드락 방지")
    void invalidTokenOnAuthPathPassesThrough() throws Exception {
        when(provider.validateToken("expired")).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/auth/google", "expired"), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("헤더 없음 → 익명 통과 (웹 데모 경로 유지)")
    void noTokenPassesThroughAnonymously() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/generations", null), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("유효 access 토큰 → 인증 컨텍스트 세팅 후 통과")
    void validAccessTokenAuthenticates() throws Exception {
        when(provider.validateToken("good")).thenReturn(true);
        when(provider.getType("good")).thenReturn(JwtTokenProvider.TYPE_ACCESS);
        when(provider.getUserId("good")).thenReturn(293L);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/replies/analysis", "good"), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(293L);
    }

    @Test
    @DisplayName("유효하지만 access 타입이 아닌 토큰 → 401 (refresh 직접 호출 차단)")
    void nonAccessTypeTokenReturns401() throws Exception {
        when(provider.validateToken("refresh")).thenReturn(true);
        when(provider.getType("refresh")).thenReturn("refresh");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/v1/generations", "refresh"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
