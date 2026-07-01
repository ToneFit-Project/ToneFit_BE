package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.ai.AiCircuitBreaker;
import com.example.tonefitserver.core.ai.FailoverProperties;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FailoverAiCorrectionClient 데코레이터 글루 테스트 — mock async 클라이언트 + 실제 차단기/스케줄러.
 */
class FailoverAiCorrectionClientTest {

    private ScheduledExecutorService scheduler;
    private AsyncAiCorrectionClient primary;
    private AsyncAiCorrectionClient fallback;

    private static final AiCorrectionResult GEMINI = new AiCorrectionResult(List.of());
    private static final AiCorrectionResult GPT = new AiCorrectionResult(List.of());

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        primary = mock(AsyncAiCorrectionClient.class);
        fallback = mock(AsyncAiCorrectionClient.class);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private FailoverAiCorrectionClient client(AiCircuitBreaker breaker) {
        return new FailoverAiCorrectionClient(primary, fallback, breaker, scheduler,
                new FailoverProperties(true, 150, 800, new FailoverProperties.Circuit(5, 3_600_000L, 1_800_000L)));
    }

    private AiCircuitBreaker closedBreaker() {
        return new AiCircuitBreaker(5, 3_600_000L, 1_800_000L, System::currentTimeMillis);
    }

    @Test
    @DisplayName("primary 성공 시 Gemini 결과, fallback 미호출")
    void primaryWins() {
        when(primary.correctAsync(any(), any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(GEMINI));

        AiCorrectionResult result = client(closedBreaker())
                .correct("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "안녕하세요.", null);

        assertThat(result).isSameAs(GEMINI);
        verify(fallback, never()).correctAsync(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("primary 즉시 실패 시 fallback(GPT) 결과")
    void immediateFailureUsesFallback() {
        when(primary.correctAsync(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("gemini 500")));
        when(fallback.correctAsync(any(), any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(GPT));

        AiCorrectionResult result = client(closedBreaker())
                .correct("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "안녕하세요.", null);

        assertThat(result).isSameAs(GPT);
    }

    @Test
    @DisplayName("차단기 OPEN 이면 primary 안 부르고 GPT 직행")
    void circuitOpenGoesFallbackDirect() {
        AiCircuitBreaker breaker = closedBreaker();
        for (int i = 0; i < 5; i++) breaker.recordPrimaryFailure();
        when(fallback.correctAsync(any(), any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(GPT));

        AiCorrectionResult result = client(breaker)
                .correct("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "안녕하세요.", null);

        assertThat(result).isSameAs(GPT);
        verify(primary, never()).correctAsync(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("primary·fallback 둘 다 실패면 예외")
    void bothFailThrows() {
        when(primary.correctAsync(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("gemini")));
        when(fallback.correctAsync(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("gpt")));

        assertThatThrownBy(() -> client(closedBreaker())
                .correct("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "안녕하세요.", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
