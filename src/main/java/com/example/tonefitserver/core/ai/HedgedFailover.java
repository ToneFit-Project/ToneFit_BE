package com.example.tonefitserver.core.ai;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 헤지드 폴오버 — primary(Gemini)와 fallback(GPT) 중 먼저 "성공"한 결과를 고른다 (PM 회신/생성·교정 폴오버).
 *
 * <p>규칙:
 * <ul>
 *   <li>primary 를 먼저 시작한다.</li>
 *   <li>primary 가 <b>즉시 실패</b>(예외 완료)하면 {@code hedgeDelay} 를 기다리지 않고 즉시 fallback 시작.</li>
 *   <li>primary 가 {@code hedgeDelay} 안에 안 끝나면 fallback 시작 — <b>primary 는 취소하지 않는다</b>
 *       (둘 다 in-flight, 결과만 버림).</li>
 *   <li>먼저 <b>성공</b>한 쪽 결과 사용. 한쪽의 실패는 race 를 끝내지 않고 반대편을 {@code deadline} 까지 기다린다.</li>
 *   <li>둘 다 실패하거나 {@code deadline} 초과 시 결과 future 를 예외 완료.</li>
 * </ul>
 *
 * <p>결과 선택만 담당한다 — 차단기 기록·동기 블로킹·예외→ErrorType 매핑은 호출자(데코레이터) 몫.
 * primary 의 자체 성공/실패는 호출자가 별도로 관찰해 차단기에 기록한다(race 승패와 무관하게).
 */
public final class HedgedFailover {

    private HedgedFailover() {
    }

    public static <T> CompletableFuture<T> run(
            Supplier<CompletableFuture<T>> primary,
            Supplier<CompletableFuture<T>> fallback,
            long hedgeDelayMillis,
            long deadlineMillis,
            ScheduledExecutorService scheduler) {

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicBoolean fallbackStarted = new AtomicBoolean(false);
        AtomicBoolean primaryFailed = new AtomicBoolean(false);
        AtomicBoolean fallbackFailed = new AtomicBoolean(false);
        AtomicReference<Throwable> primaryError = new AtomicReference<>();
        AtomicReference<Throwable> fallbackError = new AtomicReference<>();

        Runnable startFallback = () -> {
            if (result.isDone()) return;
            if (!fallbackStarted.compareAndSet(false, true)) return;
            CompletableFuture<T> fb;
            try {
                fb = fallback.get();
            } catch (Throwable t) {
                fallbackError.set(t);
                fallbackFailed.set(true);
                failIfBothFailed(result, primaryFailed, fallbackFailed, primaryError, fallbackError);
                return;
            }
            fb.whenComplete((v, ex) -> {
                if (ex == null) {
                    result.complete(v);
                } else {
                    fallbackError.set(ex);
                    fallbackFailed.set(true);
                    failIfBothFailed(result, primaryFailed, fallbackFailed, primaryError, fallbackError);
                }
            });
        };

        CompletableFuture<T> primaryFuture;
        try {
            primaryFuture = primary.get();
        } catch (Throwable t) {
            // primary 생성 자체가 실패 — 즉시 fallback.
            primaryError.set(t);
            primaryFailed.set(true);
            startFallback.run();
            failIfBothFailed(result, primaryFailed, fallbackFailed, primaryError, fallbackError);
            return result;
        }

        primaryFuture.whenComplete((v, ex) -> {
            if (ex == null) {
                result.complete(v);
            } else {
                primaryError.set(ex);
                primaryFailed.set(true);
                startFallback.run();   // 즉시 실패 → hedgeDelay 무시하고 바로 fallback
                failIfBothFailed(result, primaryFailed, fallbackFailed, primaryError, fallbackError);
            }
        });

        ScheduledFuture<?> hedgeTask = scheduler.schedule(() -> {
            if (!primaryFuture.isDone()) startFallback.run();   // 느림 → hedge
        }, hedgeDelayMillis, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> deadlineTask = scheduler.schedule(() ->
                        result.completeExceptionally(new TimeoutException("hedged failover deadline exceeded")),
                deadlineMillis, TimeUnit.MILLISECONDS);

        result.whenComplete((v, ex) -> {
            hedgeTask.cancel(false);
            deadlineTask.cancel(false);
        });
        return result;
    }

    private static void failIfBothFailed(CompletableFuture<?> result,
                                         AtomicBoolean primaryFailed, AtomicBoolean fallbackFailed,
                                         AtomicReference<Throwable> primaryError,
                                         AtomicReference<Throwable> fallbackError) {
        if (primaryFailed.get() && fallbackFailed.get()) {
            Throwable cause = fallbackError.get() != null ? fallbackError.get() : primaryError.get();
            RuntimeException ex = new RuntimeException("both primary and fallback failed", cause);
            Throwable other = primaryError.get();
            if (other != null && other != cause) ex.addSuppressed(other);
            result.completeExceptionally(ex);
        }
    }
}
