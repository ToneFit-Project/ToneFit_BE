package com.example.tonefitserver.core.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@link AiHttpTransport} 의 JDK {@code HttpClient.sendAsync} 구현. 논블로킹 NIO 라 in-flight 호출이
 * 워커 스레드를 잡지 않는다 — 헤지(Gemini 미취소 + GPT 병행) 시 스레드 폭증을 막는다.
 *
 * <p>폴오버 활성(@code ai.failover.enabled=true})일 때만 빈 등록.
 */
@Component
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
public class JdkHttpTransport implements AiHttpTransport {

    private static final int BODY_SNIPPET_MAX = 500;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public CompletableFuture<String> postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);

        return httpClient
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        String snippet = response.body() == null ? "" : response.body();
                        if (snippet.length() > BODY_SNIPPET_MAX) {
                            snippet = snippet.substring(0, BODY_SNIPPET_MAX);
                        }
                        throw new AiHttpException(response.statusCode(), snippet);
                    }
                    return response.body();
                });
    }
}
