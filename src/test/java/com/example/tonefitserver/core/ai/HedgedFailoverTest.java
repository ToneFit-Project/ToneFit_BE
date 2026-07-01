package com.example.tonefitserver.core.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HedgedFailover 단위 테스트 — 타이밍 기반(여유 마진). Spring/Docker 불필요.
 */
class HedgedFailoverTest {

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private Supplier<CompletableFuture<String>> success(String value, long delayMs) {
        return () -> CompletableFuture.supplyAsync(() -> value,
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
    }

    private Supplier<CompletableFuture<String>> failure(long delayMs) {
        return () -> CompletableFuture.supplyAsync(() -> {
            throw new RuntimeException("boom");
        }, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("primary 가 hedge 전에 성공하면 primary 결과")
    void primaryWinsBeforeHedge() throws Exception {
        CompletableFuture<String> r = HedgedFailover.run(
                success("GEMINI", 60), success("GPT", 40), 300, 1500, scheduler);
        assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("GEMINI");
    }

    @Test
    @DisplayName("primary 가 느리면 hedge 로 시작한 fallback 이 먼저 성공해 GPT 결과")
    void fallbackWinsWhenPrimarySlow() throws Exception {
        CompletableFuture<String> r = HedgedFailover.run(
                success("GEMINI", 800), success("GPT", 40), 300, 1500, scheduler);
        assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("GPT");
    }

    @Test
    @DisplayName("hedge 후에도 primary 가 fallback 보다 먼저 성공하면 primary 결과")
    void primaryWinsAfterHedge() throws Exception {
        CompletableFuture<String> r = HedgedFailover.run(
                success("GEMINI", 350), success("GPT", 400), 300, 1500, scheduler);
        assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("GEMINI");
    }

    @Test
    @DisplayName("primary 즉시 실패면 hedge 안 기다리고 바로 fallback 시작")
    void immediatePrimaryFailureStartsFallbackNow() throws Exception {
        long start = System.nanoTime();
        CompletableFuture<String> r = HedgedFailover.run(
                failure(20), success("GPT", 40), 300, 1500, scheduler);
        assertThat(r.get(2, TimeUnit.SECONDS)).isEqualTo("GPT");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(250);   // hedge(300) 안 기다림
    }

    @Test
    @DisplayName("primary·fallback 둘 다 실패하면 예외 완료")
    void bothFail() {
        CompletableFuture<String> r = HedgedFailover.run(
                failure(20), failure(60), 300, 1500, scheduler);
        assertThatThrownBy(() -> r.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("deadline 초과 시 TimeoutException")
    void deadlineExceeded() {
        CompletableFuture<String> r = HedgedFailover.run(
                success("GEMINI", 3000), success("GPT", 3000), 100, 400, scheduler);
        assertThatThrownBy(() -> r.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }
}
