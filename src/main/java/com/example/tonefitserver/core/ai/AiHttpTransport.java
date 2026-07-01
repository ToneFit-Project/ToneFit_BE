package com.example.tonefitserver.core.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 비동기 JSON POST 전송. 폴오버 헤지를 위해 호출당 타임아웃 + 논블로킹(스레드 비점유)이 필요해서
 * Spring RestClient(팩토리 고정 타임아웃·블로킹) 대신 별도 추상화로 둔다.
 *
 * <p>구현은 {@code HttpClient.sendAsync} 기반. 4xx/5xx 는 {@link AiHttpException} 으로 예외 완료,
 * 타임아웃·네트워크 오류는 그대로 future 예외 완료(HttpTimeoutException/IOException).
 */
public interface AiHttpTransport {

    /**
     * @param uri     전체 URI (baseUrl + path)
     * @param headers 추가 헤더 (Content-Type: application/json 은 구현이 붙인다)
     * @param body    직렬화된 JSON 문자열
     * @param timeout 요청별 타임아웃
     * @return 응답 본문 문자열 (2xx). 4xx/5xx·타임아웃·IO 는 예외 완료.
     */
    CompletableFuture<String> postJson(URI uri, Map<String, String> headers, String body, Duration timeout);
}
