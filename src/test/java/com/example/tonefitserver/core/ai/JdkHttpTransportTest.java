package com.example.tonefitserver.core.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JdkHttpTransport 통합 테스트 — in-process HttpServer 로 sendAsync·상태코드 처리 검증.
 */
class JdkHttpTransportTest {

    private HttpServer server;
    private final JdkHttpTransport transport = new JdkHttpTransport();

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private URI startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @Test
    @DisplayName("2xx 응답 본문을 반환한다")
    void returnsBodyOn2xx() throws Exception {
        URI uri = startServer(200, "{\"ok\":true}");
        String result = transport.postJson(uri, Map.of("X-Test", "1"), "{}", Duration.ofSeconds(5))
                .get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("4xx/5xx 는 AiHttpException 으로 예외 완료")
    void failsOnErrorStatus() throws Exception {
        URI uri = startServer(500, "boom");
        assertThatThrownBy(() ->
                transport.postJson(uri, Map.of(), "{}", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AiHttpException.class);
    }
}
