package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.ai.AiCircuitBreaker;
import com.example.tonefitserver.core.ai.FailoverProperties;
import com.example.tonefitserver.core.ai.HedgedFailover;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Range;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 교정 AI 폴오버 데코레이터 — primary(Gemini) + fallback(GPT) 헤지 + 차단기. ai.failover.enabled=true 시
 * {@link AiCorrectionClient} 의 {@link Primary} 빈으로 등록되어 CorrectionService 가 이걸 호출한다.
 * 동작 규칙은 생성 폴오버와 동일(차단기 OPEN→GPT 직행 / 그 외 헤지 / Gemini 자체 결과로 차단기 기록).
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
public class FailoverAiCorrectionClient implements AiCorrectionClient {

    private static final long BLOCK_MARGIN_MS = 2_000L;

    private final AsyncAiCorrectionClient primary;
    private final AsyncAiCorrectionClient fallback;
    private final AiCircuitBreaker breaker;
    private final ScheduledExecutorService scheduler;
    private final long hedgeDelayMs;
    private final long deadlineMs;

    public FailoverAiCorrectionClient(
            @Qualifier("geminiAsyncCorrectionClient") AsyncAiCorrectionClient primary,
            @Qualifier("openAiCorrectionClient") AsyncAiCorrectionClient fallback,
            @Qualifier("correctionCircuitBreaker") AiCircuitBreaker breaker,
            ScheduledExecutorService aiFailoverScheduler,
            FailoverProperties properties) {
        this.primary = primary;
        this.fallback = fallback;
        this.breaker = breaker;
        this.scheduler = aiFailoverScheduler;
        this.hedgeDelayMs = properties.hedgeDelayMs();
        this.deadlineMs = properties.deadlineMs();
    }

    @Override
    public AiCorrectionResult correct(String promptContent, Receiver receiver, Purpose purpose,
                                      String original, List<Range> protectedRanges) {
        if (!breaker.primaryAllowed()) {
            log.info("ai_failover op=correction route=fallback_direct reason=circuit_open");
            return block(fallback.correctAsync(promptContent, receiver, purpose, original, protectedRanges));
        }

        Supplier<CompletableFuture<AiCorrectionResult>> primarySupplier = () -> {
            CompletableFuture<AiCorrectionResult> f =
                    primary.correctAsync(promptContent, receiver, purpose, original, protectedRanges);
            // 차단기 기록: Gemini 자체 성공/실패(레이스 승패 무관). copy() 로 관찰만 — 헤지가 쓰는 f 는 안 건드린다.
            f.copy().orTimeout(deadlineMs, TimeUnit.MILLISECONDS).whenComplete((v, ex) -> {
                if (ex == null) breaker.recordPrimarySuccess();
                else breaker.recordPrimaryFailure();
            });
            return f;
        };
        Supplier<CompletableFuture<AiCorrectionResult>> fallbackSupplier =
                () -> fallback.correctAsync(promptContent, receiver, purpose, original, protectedRanges);

        return block(HedgedFailover.run(primarySupplier, fallbackSupplier, hedgeDelayMs, deadlineMs, scheduler));
    }

    private AiCorrectionResult block(CompletableFuture<AiCorrectionResult> future) {
        try {
            return future.get(deadlineMs + BLOCK_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("AI correction failover failed",
                    e.getCause() != null ? e.getCause() : e);
        } catch (Exception e) {
            throw new IllegalStateException("AI correction failover failed", e);
        }
    }
}
