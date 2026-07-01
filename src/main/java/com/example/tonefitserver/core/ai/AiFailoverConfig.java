package com.example.tonefitserver.core.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 폴오버(헤지 + 차단기) 구성. {@code ai.failover.enabled=true} 일 때만 활성 — 기본 false 라 현행 동작 무영향.
 *
 * <p>Phase 2: OpenAI 설정·전송·클라이언트 빈 활성화.
 * <p>Phase 3(예정): 헤지용 ScheduledExecutorService, 기능별 AiCircuitBreaker, FailoverAi*Client 데코레이터 와이어링.
 */
@Configuration
@ConditionalOnProperty(name = "ai.failover.enabled", havingValue = "true")
@EnableConfigurationProperties(OpenAiProperties.class)
public class AiFailoverConfig {
}
