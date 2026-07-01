package com.example.tonefitserver.core.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * AI 폴오버(헤지 + 차단기) 구성. {@code ai.failover.enabled=true} 일 때만 활성 — 기본 false 라 현행 동작 무영향.
 *
 * <p>공용 인프라: 헤지 타이머용 ScheduledExecutorService(데몬), 기능별 {@link AiCircuitBreaker} 빈.
 * 생성·교정 모델이 달라(2.5/3.5) 실패가 독립적이므로 차단기를 기능별로 분리한다.
 */
@Configuration
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
@EnableConfigurationProperties({OpenAiProperties.class, FailoverProperties.class})
public class AiFailoverConfig {

    /** 헤지 delay·deadline 타이머 전용. 논블로킹 HTTP 라 소수 스레드로 충분. 데몬 → 종료 방해 안 함. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService aiFailoverScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ai-failover-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public AiCircuitBreaker generationCircuitBreaker(FailoverProperties properties) {
        return newBreaker(properties);
    }

    @Bean
    public AiCircuitBreaker correctionCircuitBreaker(FailoverProperties properties) {
        return newBreaker(properties);
    }

    private AiCircuitBreaker newBreaker(FailoverProperties properties) {
        FailoverProperties.Circuit circuit = properties.circuit();
        return new AiCircuitBreaker(circuit.failureThreshold(), circuit.windowMs(), circuit.openMs(),
                System::currentTimeMillis);
    }
}
