package com.example.tonefitserver.domain.event;

import com.example.tonefitserver.core.web.RequestContext;
import com.example.tonefitserver.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * BE 가 자동 발화하는 이벤트를 기록한다 (FUNC-Amp-03 — "BE API 를 거치는" 동작만).
 * 호출은 도메인 서비스의 @Transactional 안에서 이뤄지며, 같은 트랜잭션 안에서 event_log INSERT 를 수행한다.
 *
 * <p>INSERT 직후 ApplicationEventPublisher 로 EventLogPersisted 도메인 이벤트를 발행한다.
 * 외부 미러링(Amplitude HTTP)은 트랜잭션 커밋 이후에 별도 @TransactionalEventListener 가 처리한다.
 *
 * <p>v0.6: 교정 세션 제거로 session 결합이 사라졌다 — 이벤트는 user + properties 만으로 구성된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventLogRepository repository;
    private final RequestContext requestContext;
    private final ApplicationEventPublisher publisher;

    public EventLog record(User user, EventType type, Map<String, Object> properties) {
        String clientEventId = UUID.randomUUID().toString();
        EventLog event = EventLog.builder()
                .clientEventId(clientEventId)
                .user(user)
                .eventType(type)
                .visitSessionId(requestContext.getVisitSessionId())
                .properties(properties)
                .build();
        EventLog saved = repository.save(event);

        publisher.publishEvent(new EventLogPersisted(
                saved.getClientEventId(),
                user.getId(),
                saved.getEventType(),
                saved.getVisitSessionId(),
                properties,
                saved.getCreatedAt()
        ));
        return saved;
    }
}
