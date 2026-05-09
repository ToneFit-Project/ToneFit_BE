package com.example.tonefitserver.domain.event;

import com.example.tonefitserver.core.web.RequestContext;
import com.example.tonefitserver.domain.session.CorrectionSession;
import com.example.tonefitserver.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 교정 흐름 각 단계의 BE 자동 발화 이벤트를 기록한다.
 * 호출은 도메인 서비스의 @Transactional 안에서 이뤄지며,
 * 같은 트랜잭션 안에서 event_log INSERT를 수행한다.
 *
 * <p>INSERT 직후 ApplicationEventPublisher 로 EventLogPersisted 도메인 이벤트를 발행한다.
 * 외부 미러링(Amplitude HTTP)은 트랜잭션 커밋 이후에 별도 @TransactionalEventListener 가 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventLogRepository repository;
    private final RequestContext requestContext;
    private final ApplicationEventPublisher publisher;

    public EventLog record(User user, EventType type, CorrectionSession session,
                           Map<String, Object> properties) {
        String clientEventId = UUID.randomUUID().toString();
        EventLog event = EventLog.builder()
                .clientEventId(clientEventId)
                .user(user)
                .eventType(type)
                .visitSessionId(requestContext.getVisitSessionId())
                .session(session)
                .properties(properties)
                .build();
        EventLog saved = repository.save(event);

        publisher.publishEvent(new EventLogPersisted(
                saved.getClientEventId(),
                user.getId(),
                saved.getEventType(),
                saved.getVisitSessionId(),
                session == null ? null : session.getId(),
                properties,
                saved.getCreatedAt()
        ));
        return saved;
    }
}
