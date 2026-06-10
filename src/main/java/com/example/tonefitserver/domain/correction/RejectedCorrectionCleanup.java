package com.example.tonefitserver.domain.correction;

import com.example.tonefitserver.domain.correction.repository.RejectedCorrectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 거절 교정 구절 90일 보존 만료분 파기. PM 요구사항 REQ-Correction FUNC-Cor-06.
 *
 * <p>매일 0시 정각 실행. {@code created_at < now-90d} 인 row 삭제.
 * (생성 메타데이터 파기 {@code GenerationMetadataCleanup} 와 동일 정책·시각)
 *
 * <p><b>단일 인스턴스 전제</b>: 멀티 인스턴스로 확장하면 모든 인스턴스가 0시에 같은 DELETE 를 실행한다.
 * 멱등이라 정합성 문제는 없고 중복 쿼리만 발생 — 그 시점에 ShedLock 등 분산 락 권장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejectedCorrectionCleanup {

    private static final int RETENTION_DAYS = 90;

    private final RejectedCorrectionRepository repository;

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = repository.deleteByCreatedAtBefore(cutoff);
        log.info("Purged {} rejected_correction rows older than {} (retention {}d)",
                deleted, cutoff, RETENTION_DAYS);
    }
}
