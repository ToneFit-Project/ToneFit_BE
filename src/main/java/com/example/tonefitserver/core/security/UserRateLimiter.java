package com.example.tonefitserver.core.security;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.exception.BusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 사용자별(계정 단위) AI 호출 한도. PM 요구사항 REQ-Limit.
 *
 * <p>정책 (FUNC-Lim-08 PM 확정으로 v0.57 변경):
 * <ul>
 *   <li><b>일일 한도는 3기능(교정·생성·회신) 합산</b> — 키 {@code daily:userId} 단일 버킷, 하루 {@code daily} 회</li>
 *   <li><b>분당 한도는 카테고리별 독립</b> — 교정·생성 {@code perMinute}, 회신은 따로 더 낮게
 *       {@code replyPerMinute} (FUNC-Lim-09)</li>
 *   <li>데모(웹, 토큰 없음)는 호출하지 않음 — 서비스 계층에서 userId == null 이면 skip</li>
 *   <li>AI 호출 직전에 차감 → 성공·실패 무관하게 1회 카운트 (FUNC-Lim-06).
 *       회신은 호출별 차감으로 통일 — 요약·파악·작성 각 1회 (PM 확정 v0.58, 재설계로 변경)</li>
 * </ul>
 *
 * <p><b>저장소</b>: Caffeine + Bucket4j in-memory. 단일 인스턴스 가정 (IP RateLimitFilter 와 동일 전제).
 * 서버 재시작 시 카운트 리셋 — 임시값 단계에서 허용. 정식 한도(FUNC-Lim-07) 확정 시 우회 방지가
 * 중요해지면 DB/Redis 기반 영속 카운터로 전환. 분산 환경도 동일하게 Bucket4j ProxyManager(Redis) 필요.
 *
 * <p>한도 값은 {@link UserLimitProperties}(application.yml + 환경변수/AWS Secret)에서 주입 —
 * Extension 재배포 없이 조정 가능 (FUNC-Lim-03).
 */
@Component
public class UserRateLimiter {

    public static final String CATEGORY_CORRECTION = "correction";
    public static final String CATEGORY_GENERATION = "generation";
    public static final String CATEGORY_REPLY = "reply";

    private final int daily;
    private final int perMinute;
    private final int replyPerMinute;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            // daily 한도 추적을 위해 최소 1일 + 여유. 미사용 키는 만료시켜 메모리 회수.
            .expireAfterAccess(Duration.ofDays(2))
            .build();

    public UserRateLimiter(UserLimitProperties properties) {
        this.daily = properties.daily();
        this.perMinute = properties.perMinute();
        this.replyPerMinute = properties.replyPerMinute();
    }

    /**
     * 일일(합산) + 분당(카테고리별) 동시 차감. 둘 중 하나라도 소진이면
     * {@link ErrorType#USER_RATE_LIMITED} (계정 잠금 아님 — 일시 제한).
     *
     * <p>분당 버킷을 먼저 차감하고 일일 버킷이 소진이면 분당 토큰을 환불해 이중 차감을 막는다
     * (두 버킷의 원자적 결합은 in-memory 단계에서 불필요한 복잡도 — 환불로 충분).
     *
     * <p>차감은 in-memory 라 호출자의 JPA 트랜잭션에 엮이지 않는다 — prepare TX 가 이후 롤백돼도
     * 차감은 환불되지 않는다(AI 호출 전 인프라 실패 시 한도 1회 손실). 임시 구현의 알려진 부정확성
     * (서버 재시작 리셋·멀티 인스턴스 분산 미집계와 동류)이며, FUNC-Lim-07 정식 값 확정 후
     * DB/Redis 영속 카운터로 전환할 때 트랜잭셔널하게 재설계하면 해소된다.
     *
     * @param category {@link #CATEGORY_CORRECTION} / {@link #CATEGORY_GENERATION} / {@link #CATEGORY_REPLY}
     * @param userId   정식 사용자 id (익명은 호출 전에 걸러야 함)
     */
    public void consume(String category, Long userId) {
        Bucket minuteBucket = minuteBucket(category, userId);
        if (!minuteBucket.tryConsume(1)) {
            throw new BusinessException(ErrorType.USER_RATE_LIMITED);
        }
        Bucket dailyBucket = buckets.get("daily:" + userId, k -> newDailyBucket());
        if (!dailyBucket.tryConsume(1)) {
            minuteBucket.addTokens(1);
            throw new BusinessException(ErrorType.USER_RATE_LIMITED);
        }
    }

    private Bucket minuteBucket(String category, Long userId) {
        return buckets.get("minute:" + category + ":" + userId,
                k -> newMinuteBucket(CATEGORY_REPLY.equals(category) ? replyPerMinute : perMinute));
    }

    private Bucket newDailyBucket() {
        // daily: 하루 단위 일괄 충전(intervally) — "하루 N회" 직관에 부합. 3기능 합산 단일 버킷.
        return Bucket.builder()
                .addLimit(Bandwidth.classic(daily, Refill.intervally(daily, Duration.ofDays(1))))
                .build();
    }

    private Bucket newMinuteBucket(int capacity) {
        // per-minute: greedy 충전(simple) — 짧은 burst 방지.
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, Duration.ofMinutes(1)))
                .build();
    }
}
