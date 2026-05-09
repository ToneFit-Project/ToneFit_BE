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
     * - read 60s: 정상 응답은 보통 8~15초이지만, prompt v4.2d 이후 changes 배열이 길어지면서
     *   극단 케이스(2,000자 + 이모지·영어·한자 다수)에서 30초 근접·초과 관찰됨. 60초로 상향.
     *   초과 시 ResourceAccessException 발생 → AI_SERVICE_ERROR 로 매핑됨.
     */
    @Bean
    public RestClient geminiRestClient(GeminiProperties properties, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
