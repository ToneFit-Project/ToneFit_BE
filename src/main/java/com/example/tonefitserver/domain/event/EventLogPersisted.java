package com.example.tonefitserver.domain.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * event_log 행이 트랜잭션 안에서 INSERT 되었음을 알리는 도메인 이벤트.
 * AFTER_COMMIT 리스너(예: AmplitudeMirror)가 외부 분석 도구로 미러링할 때 사용한다.
 *
 * <p>JPA 세션 종료 후에도 안전하게 사용할 수 있도록 EventLog 엔티티의 lazy 필드를
 * 미리 평면화한 스냅샷으로 전달한다.
 */
public record EventLogPersisted(
        String clientEventId,
        Long userId,
        EventType eventType,
        String visitSessionId,
        Long sessionId,
        Map<String, Object> properties,
        LocalDateTime createdAt
) {
}
