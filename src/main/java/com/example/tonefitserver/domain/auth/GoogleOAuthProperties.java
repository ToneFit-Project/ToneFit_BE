package com.example.tonefitserver.domain.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Google OAuth 검증 설정. ID token 의 audience 가 여기 등록된 client-id 중 하나여야 한다.
 *
 * <p>운영에선 FE 웹 클라이언트 ID 한 개만 등록. 여러 환경(웹/모바일/Extension)으로 확장 시
 * 리스트로 추가.
 */
@ConfigurationProperties(prefix = "google.oauth")
public record GoogleOAuthProperties(
        List<String> clientIds
) {
}
