package com.example.tonefitserver.domain.event;

import com.example.tonefitserver.domain.session.CorrectionSession;
import com.example.tonefitserver.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "event_log")
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false, unique = true)
    private String clientEventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "visit_session_id", nullable = false)
    private String visitSessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private CorrectionSession session;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> properties;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public EventLog(String clientEventId, User user, EventType eventType,
                    String visitSessionId, CorrectionSession session,
                    Map<String, Object> properties) {
        this.clientEventId = clientEventId;
        this.user = user;
        this.eventType = eventType;
        this.visitSessionId = visitSessionId;
        this.session = session;
        this.properties = properties;
    }
}
