package com.example.tonefitserver.domain.correction.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini")
public class GeminiConfig {

    /**
     * Gemini 호출 타임아웃. 무한 대기로 워커가 영구 점유되지 않도록 명시.
     * - connect 5s: TCP 핸드셰이크 시간. 대부분 1초 미만, 5초면 충분
     * - read 30s: PM 요구사항(FUNC-Ext-10) — AI 호출 30초 타임아웃. 초과 시 호출 중단·타임아웃 오류 반환.
     *   v0.5 부터 교정은 corrected_email 전문이 빠진 changes 배열만, 생성은 본문만 반환하므로
     *   출력 토큰이 줄어 30초로 단축 가능. correction/generation 공유.
     *   초과 시 ResourceAccessException 발생 → AI_SERVICE_ERROR 로 매핑됨.
     */
    @Bean
    public RestClient geminiRestClient(GeminiProperties properties, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
