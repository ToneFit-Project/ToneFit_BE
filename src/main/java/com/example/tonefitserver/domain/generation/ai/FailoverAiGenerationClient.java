package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.ai.AiCircuitBreaker;
import com.example.tonefitserver.core.ai.FailoverProperties;
import com.example.tonefitserver.core.ai.HedgedFailover;
import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 생성 AI 폴오버 데코레이터 — primary(Gemini) + fallback(GPT) 헤지 + 차단기. ai.failover.enabled=true 시
 * {@link AiGenerationClient} 의 {@link Primary} 빈으로 등록되어 GenerationService 가 이걸 호출한다.
 *
 * <ul>
 *   <li>차단기 OPEN → GPT 직행(헤지 없음).</li>
 *   <li>그 외 → {@link HedgedFailover}: 즉시 실패는 바로 GPT, 느림은 hedgeDelay 후 GPT 병행, 먼저 성공한 쪽 사용.</li>
 *   <li>차단기 기록은 Gemini 자체 결과(레이스 승패 무관, deadline 내 성공/실패) 기준.</li>
 * </ul>
 *
 * <p>동기 인터페이스라 헤지 future 를 블로킹해 반환한다(워커 1개 점유 — 기존과 동일). 총 실패는
 * 예외를 던져 GenerationService 가 AI_SERVICE_ERROR 로 매핑한다.
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
public class FailoverAiGenerationClient implements AiGenerationClient {

    private static final long BLOCK_MARGIN_MS = 2_000L;

    private final AsyncAiGenerationClient primary;
    private final AsyncAiGenerationClient fallback;
    private final AiCircuitBreaker breaker;
    private final ScheduledExecutorService scheduler;
    private final long hedgeDelayMs;
    private final long deadlineMs;

    public FailoverAiGenerationClient(
            @Qualifier("geminiAsyncGenerationClient") AsyncAiGenerationClient primary,
            @Qualifier("openAiGenerationClient") AsyncAiGenerationClient fallback,
            @Qualifier("generationCircuitBreaker") AiCircuitBreaker breaker,
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
    public AiGenerationResult generate(String promptContent, Receiver receiver, Purpose purpose, String briefContent) {
        if (!breaker.primaryAllowed()) {
            log.info("ai_failover op=generation route=fallback_direct reason=circuit_open");
            return block(fallback.generateAsync(promptContent, receiver, purpose, briefContent));
        }

        Supplier<CompletableFuture<AiGenerationResult>> primarySupplier = () -> {
            CompletableFuture<AiGenerationResult> f = primary.generateAsync(promptContent, receiver, purpose, briefContent);
            // 차단기 기록: Gemini 자체 성공/실패(레이스 승패 무관). copy() 로 관찰만 — 헤지가 쓰는 f 는 안 건드린다.
            // deadline 내 미완료 = 하드 실패(30s 초과)로 집계.
            f.copy().orTimeout(deadlineMs, TimeUnit.MILLISECONDS).whenComplete((v, ex) -> {
                if (ex == null) breaker.recordPrimarySuccess();
                else breaker.recordPrimaryFailure();
            });
            return f;
        };
        Supplier<CompletableFuture<AiGenerationResult>> fallbackSupplier =
                () -> fallback.generateAsync(promptContent, receiver, purpose, briefContent);

        return block(HedgedFailover.run(primarySupplier, fallbackSupplier, hedgeDelayMs, deadlineMs, scheduler));
    }

    private AiGenerationResult block(CompletableFuture<AiGenerationResult> future) {
        try {
            return future.get(deadlineMs + BLOCK_MARGIN_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("AI generation failover failed",
                    e.getCause() != null ? e.getCause() : e);
        } catch (Exception e) {
            throw new IllegalStateException("AI generation failover failed", e);
        }
    }
}
