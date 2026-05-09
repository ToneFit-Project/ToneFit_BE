package com.example.tonefitserver.domain.event.amplitude;

import com.example.tonefitserver.domain.event.EventLogPersisted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * event_log INSERT 가 커밋된 직후 Amplitude HTTP API 로 미러링한다.
 *
 * <p>정책:
 * <ul>
 *   <li>amplitude.enabled=true 일 때만 빈 등록</li>
 *   <li>1회 재시도 (1초 backoff)</li>
 *   <li>최종 실패 시 로그만 남기고 예외 전파하지 않음 (도메인 트랜잭션은 이미 커밋됨)</li>
 *   <li>insert_id 는 BE 가 생성한 client_event_id 를 그대로 사용 → 멱등 보장</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "amplitude.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AmplitudeMirror {

    private static final String HTTP_API_PATH = "/2/httpapi";
    private static final long RETRY_BACKOFF_MS = 1_000L;

    private final RestClient amplitudeRestClient;
    private final AmplitudeProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventLogPersisted(EventLogPersisted event) {
        Map<String, Object> body = buildBody(event);
        try {
            send(body);
        } catch (Exception first) {
            log.warn("Amplitude send failed (1st), retrying once: type={}, insertId={}, cause={}",
                    event.eventType(), event.clientEventId(), first.toString());
            try {
                Thread.sleep(RETRY_BACKOFF_MS);
                send(body);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Amplitude retry interrupted: insertId={}", event.clientEventId());
            } catch (Exception second) {
                log.warn("Amplitude send failed (final): type={}, insertId={}, cause={}",
                        event.eventType(), event.clientEventId(), second.toString());
            }
        }
    }

    private void send(Map<String, Object> body) {
        amplitudeRestClient.post()
                .uri(HTTP_API_PATH)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> buildBody(EventLogPersisted event) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("user_id", String.valueOf(event.userId()));
        ev.put("event_type", event.eventType().amplitudeName());
        ev.put("insert_id", event.clientEventId());
        ev.put("time", event.createdAt() == null
                ? System.currentTimeMillis()
                : event.createdAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        Map<String, Object> eventProperties = event.properties() == null
                ? new HashMap<>() : new HashMap<>(event.properties());
        eventProperties.put("visit_session_id", event.visitSessionId());
        if (event.sessionId() != null) {
            eventProperties.put("correction_session_id", event.sessionId());
        }
        ev.put("event_properties", eventProperties);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", properties.apiKey());
        body.put("events", List.of(ev));
        return body;
    }
}
