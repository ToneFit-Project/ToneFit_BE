package com.example.tonefitserver.domain.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * GoogleIdTokenVerifier 빈 구성. ID token 의 서명·issuer·audience·만료를 한 번에 검증한다.
 *
 * <p>{@link GoogleOAuthProperties#clientIds()} 에 등록된 client-id 들이 audience whitelist 로
 * 들어간다. 운영용 키는 AwsSecretsManagerEnvironmentInitializer 가 주입하는 환경변수에서 가져온다.
 */
@Configuration
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleIdTokenConfig {

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(GoogleOAuthProperties properties) {
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                .setAudience(properties.clientIds())
                .build();
    }
}
