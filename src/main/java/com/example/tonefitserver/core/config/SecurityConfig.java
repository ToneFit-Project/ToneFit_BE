package com.example.tonefitserver.core.config;

import com.example.tonefitserver.core.security.JwtAuthenticationFilter;
import com.example.tonefitserver.core.security.RateLimitFilter;
import com.example.tonefitserver.core.security.RateLimitProperties;
import com.example.tonefitserver.core.security.UserLimitProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({UserLimitProperties.class, RateLimitProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${app.security.content-security-policy}") String contentSecurityPolicy) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            // QA 제안: 응답 보안 헤더 강화. 기본 헤더(nosniff·X-Frame-Options DENY·no-store
            // Cache-Control·X-XSS-Protection 0)는 유지되고, 아래에서 추가/세부 설정한다.
            .headers(headers -> headers
                // 클릭재킹: 기본 X-Frame-Options DENY 유지 + CSP frame-ancestors 로 현대 브라우저까지 커버.
                .frameOptions(frame -> frame.deny())
                // HSTS: TLS 종단은 CloudFront — CloudFront→EC2 구간이 HTTP 라 현재는 미발화(no-op).
                // 전구간 HTTPS 전환(인프라팀 보류) 시 forward-headers 인식으로 발화되는 대비 설정.
                // 로컬/도커(HTTP)는 secure 아님 → 미발화(의도된 동작 — localhost 에 HSTS 가 걸리는 것 회피).
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .preload(true)
                    .maxAgeInSeconds(31_536_000))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                // CSP 는 프로파일별 외부화(app.security.content-security-policy):
                //  - prod: Swagger 비활성이라 strict(default-src 'none') 가능.
                //  - 로컬/도커: Swagger UI inline script/style 위해 'unsafe-inline' 허용한 완화판.
                // JSON 응답엔 사실상 무의미(JSON 은 렌더링되지 않음)하지만 방어적으로 둔다.
                .contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy))
                .permissionsPolicyHeader(permissions -> permissions.policy(
                    "geolocation=(), camera=(), microphone=(), payment=(), usb=()"))
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/v1/auth/**",
                        "/actuator/health",
                        "/test/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"// /test/** 컨트롤러 자체는 prod 프로파일에서 비등록(@Profile)
                ).permitAll()
                // 웹 데모 생성: 토큰 없이 호출 가능(익명 토큰 폐지). Extension 은 Bearer 토큰을 보내면
                // JwtAuthenticationFilter 가 principal 을 채워 정식으로 처리됨. IP rate limit 으로 남용 방어.
                // 단, 무효 Bearer(만료 등)를 실으면 필터가 401 로 거절 — 로그인 사용자의 데모 강등·만료 은폐 방지.
                .requestMatchers(HttpMethod.POST, "/api/v1/generations").permitAll()
                .anyRequest().authenticated()
            )
            // 토큰 없음·만료·서명 무효 등 인증 실패는 401 + ApiResponse 형식.
            // 기본값(403 AccessDenied) 그대로 두면 FE 가 refresh 트리거 분기를 못 잡음.
            .exceptionHandling(eh -> eh.authenticationEntryPoint(unauthorizedEntryPoint()))
            // 등록 순서 주의: anchor 로 쓸 필터(JwtAuthenticationFilter)를 먼저 등록한 뒤
            // 그것을 anchor 로 하는 필터(RateLimitFilter)를 등록해야 filterOrders 에 인식됨.
            // RateLimit 이 가장 먼저 실행되어 /generations 같은 permitAll 경로에도 적용됨.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"토큰 없음 또는 만료\"}}"
                            .getBytes(StandardCharsets.UTF_8));
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // allowCredentials(true) + 와일드카드 패턴은 setAllowedOrigins 로 불가 → setAllowedOriginPatterns 사용.
        config.setAllowedOriginPatterns(List.of(
                "https://tonefit-six.vercel.app",
                "http://localhost:8080",
                "https://tonefit.kr",
                "chrome-extension://hccpncocbnbphkmandkcmnefolgfhcgi",  // Extension 운영 ID (웹스토어 게시)
                "chrome-extension://mlideabaeifblifdknaalinclaobnkgh"   // FE 테스트용 확장 (FE 요청 — 테스트 종료 시 제거 가능)
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
