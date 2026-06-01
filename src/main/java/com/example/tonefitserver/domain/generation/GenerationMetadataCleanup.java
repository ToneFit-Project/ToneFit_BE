package com.example.tonefitserver.domain.generation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 생성 메타데이터 90일 보존 만료분 파기. PM 요구사항 REQ-Extension FUNC-Ext-11 #2.
 *
 * <p>매일 0시 정각 실행. {@code created_at < now-90d} 인 row 삭제.
 *
 * <p><b>단일 인스턴스 전제</b>: in-memory rate limit 과 동일한 가정. 멀티 인스턴스로 확장하면
 * 모든 인스턴스가 0시에 같은 DELETE 를 실행한다. 삭제 쿼리가 멱등이라 정합성 문제는 없고
 * 중복 쿼리만 발생 — 그 시점에 ShedLock 등 분산 락으로 단일 실행 보장 권장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationMetadataCleanup {

    private static final int RETENTION_DAYS = 90;

    private final GenerationMetadataRepository repository;

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = repository.deleteByCreatedAtBefore(cutoff);
        log.info("Purged {} generation_metadata rows older than {} (retention {}d)",
                deleted, cutoff, RETENTION_DAYS);
    }
}
