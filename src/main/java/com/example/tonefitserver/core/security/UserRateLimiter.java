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
 * <p>정책:
 * <ul>
 *   <li>키 = {@code category:userId} — correction/generation 각각 독립 카운트 (FUNC-Lim-03 "같게" = 각 100/10)</li>
 *   <li>두 한도 동시 적용: 하루 {@code daily} 회 + 분당 {@code perMinute} 회 (둘 중 하나라도 소진 시 거부)</li>
 *   <li>익명(데모, REQ-Demo)은 호출하지 않음 — 서비스 계층에서 is_guest 면 skip</li>
 *   <li>AI 호출 직전에 차감 → 성공·실패 무관하게 1회 카운트 (FUNC-Lim-06)</li>
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

    private final int daily;
    private final int perMinute;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            // daily 한도 추적을 위해 최소 1일 + 여유. 미사용 키는 만료시켜 메모리 회수.
            .expireAfterAccess(Duration.ofDays(2))
            .build();

    public UserRateLimiter(UserLimitProperties properties) {
        this.daily = properties.daily();
        this.perMinute = properties.perMinute();
    }

    /**
     * 한도 1회 차감. 초과 시 {@link ErrorType#USER_RATE_LIMITED} 던짐 (계정 잠금 아님 — 일시 제한).
     *
     * <p>차감은 in-memory 라 호출자의 JPA 트랜잭션에 엮이지 않는다 — prepare TX 가 이후 롤백돼도
     * 차감은 환불되지 않는다(AI 호출 전 인프라 실패 시 한도 1회 손실). 임시 구현의 알려진 부정확성
     * (서버 재시작 리셋·멀티 인스턴스 분산 미집계와 동류)이며, FUNC-Lim-07 정식 값 확정 후
     * DB/Redis 영속 카운터로 전환할 때 트랜잭셔널하게 재설계하면 해소된다.
     *
     * @param category {@link #CATEGORY_CORRECTION} 또는 {@link #CATEGORY_GENERATION}
     * @param userId   정식 사용자 id (익명은 호출 전에 걸러야 함)
     */
    public void consume(String category, Long userId) {
        String key = category + ":" + userId;
        Bucket bucket = buckets.get(key, k -> newBucket());
        if (!bucket.tryConsume(1)) {
            throw new BusinessException(ErrorType.USER_RATE_LIMITED);
        }
    }

    private Bucket newBucket() {
        // daily: 하루 단위 일괄 충전(intervally) — "하루 N회" 직관에 부합.
        Bandwidth dailyLimit = Bandwidth.classic(daily, Refill.intervally(daily, Duration.ofDays(1)));
        // per-minute: greedy 충전(simple) — 짧은 burst 방지.
        Bandwidth minuteLimit = Bandwidth.simple(perMinute, Duration.ofMinutes(1));
        return Bucket.builder()
                .addLimit(dailyLimit)
                .addLimit(minuteLimit)
                .build();
    }
}
