package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.ai.AiCircuitBreaker;
import com.example.tonefitserver.core.ai.FailoverProperties;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * FailoverAiGenerationClient 데코레이터 글루 테스트 — mock async 클라이언트 + 실제 차단기/스케줄러.
 */
class FailoverAiGenerationClientTest {

    private ScheduledExecutorService scheduler;
    private AsyncAiGenerationClient primary;
    private AsyncAiGenerationClient fallback;

    private static final AiGenerationResult GEMINI = new AiGenerationResult("G제목", "G본문");
    private static final AiGenerationResult GPT = new AiGenerationResult("P제목", "P본문");

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        primary = mock(AsyncAiGenerationClient.class);
        fallback = mock(AsyncAiGenerationClient.class);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private FailoverAiGenerationClient client(AiCircuitBreaker breaker) {
        return new FailoverAiGenerationClient(primary, fallback, breaker, scheduler,
                new FailoverProperties(true, 150, 800, new FailoverProperties.Circuit(5, 3_600_000L, 1_800_000L)));
    }

    private AiCircuitBreaker closedBreaker() {
        return new AiCircuitBreaker(5, 3_600_000L, 1_800_000L, System::currentTimeMillis);
    }

    @Test
    @DisplayName("primary 성공 시 Gemini 결과, fallback 미호출")
    void primaryWins() {
        when(primary.generateAsync(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(GEMINI));

        AiGenerationResult result = client(closedBreaker())
                .generate("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "brief");

        assertThat(result).isEqualTo(GEMINI);
        verify(fallback, never()).generateAsync(any(), any(), any(), any());
    }

    @Test
    @DisplayName("primary 즉시 실패 시 fallback(GPT) 결과")
    void immediateFailureUsesFallback() {
        when(primary.generateAsync(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("gemini 500")));
        when(fallback.generateAsync(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(GPT));

        AiGenerationResult result = client(closedBreaker())
                .generate("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "brief");

        assertThat(result).isEqualTo(GPT);
    }

    @Test
    @DisplayName("차단기 OPEN 이면 primary 안 부르고 GPT 직행")
    void circuitOpenGoesFallbackDirect() {
        AiCircuitBreaker breaker = closedBreaker();
        for (int i = 0; i < 5; i++) breaker.recordPrimaryFailure();   // OPEN 으로 트립
        when(fallback.generateAsync(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(GPT));

        AiGenerationResult result = client(breaker)
                .generate("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "brief");

        assertThat(result).isEqualTo(GPT);
        verify(primary, never()).generateAsync(any(), any(), any(), any());
    }

    @Test
    @DisplayName("primary·fallback 둘 다 실패면 예외")
    void bothFailThrows() {
        when(primary.generateAsync(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("gemini")));
        when(fallback.generateAsync(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("gpt")));

        assertThatThrownBy(() -> client(closedBreaker())
                .generate("p", Receiver.DIRECT_SUPERVISOR, Purpose.NOTICE, "brief"))
                .isInstanceOf(IllegalStateException.class);
    }
}
