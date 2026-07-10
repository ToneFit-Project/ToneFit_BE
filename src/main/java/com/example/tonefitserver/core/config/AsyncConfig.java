package com.example.tonefitserver.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 실행 설정. 현재 소비처는 Amplitude 미러링뿐 — 요청 스레드에서 외부 HTTP 왕복
 * (+실패 시 1초 백오프 재시도)을 떼어내 응답 지연·워커 점유를 막는다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Amplitude 미러링 전용 소형 풀. 미러는 best-effort 이고 정본은 event_log 라,
     * 큐가 가득 차면(외부 장애로 적체) 조용히 버린다(DiscardPolicy) — 적체가 요청 스레드로
     * 역류(CallerRuns)하거나 무한 큐로 메모리를 키우는 것보다 이벤트 유실이 낫다.
     */
    @Bean("amplitudeExecutor")
    public ThreadPoolTaskExecutor amplitudeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("amplitude-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return executor;
    }
}
