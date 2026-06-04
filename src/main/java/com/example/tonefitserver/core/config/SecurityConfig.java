package com.example.tonefitserver.core.config;

import com.example.tonefitserver.core.security.JwtAuthenticationFilter;
import com.example.tonefitserver.core.security.RateLimitFilter;
import com.example.tonefitserver.core.security.UserLimitProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
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
@EnableConfigurationProperties(UserLimitProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
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
        config.setAllowedOrigins(List.of(
                "https://tonefit-six.vercel.app",
                "http://localhost:8080",
                "https://tonefit.kr"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
