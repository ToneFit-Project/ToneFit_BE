package com.example.tonefitserver.domain.correction.model;

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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "correction_feedback")
public class CorrectionFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private CorrectionSession session;

    @Column(name = "\"index\"", nullable = false)
    private int index;

    @Column(nullable = false)
    private int start;

    @Column(name = "\"end\"", nullable = false)
    private int end;

    @Column(columnDefinition = "text", nullable = false)
    private String original;

    @Column(columnDefinition = "text", nullable = false)
    private String corrected;

    @Column(columnDefinition = "text", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Label label;

    @Column(nullable = false)
    private double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> appliedRules;

    @Enumerated(EnumType.STRING)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_primary")
    private ReasonPrimary reasonPrimary;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_secondary")
    private ReasonSecondary reasonSecondary;

    @Column(name = "reason_text")
    private String reasonText;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public CorrectionFeedback(User user, CorrectionSession session, int index,
                              int start, int end, String original, String corrected,
                              String reason, Label label, double confidence,
                              List<String> appliedRules) {
        this.user = user;
        this.session = session;
        this.index = index;
        this.start = start;
        this.end = end;
        this.original = original;
        this.corrected = corrected;
        this.reason = reason;
        this.label = label;
        this.confidence = confidence;
        this.appliedRules = appliedRules;
    }

    public void reject(ReasonPrimary reasonPrimary, ReasonSecondary reasonSecondary, String reasonText) {
        this.action = Action.REJECTED;
        this.reasonPrimary = reasonPrimary;
        this.reasonSecondary = reasonSecondary;
        this.reasonText = reasonText;
    }

    public void accept() {
        this.action = Action.ACCEPTED;
    }
}
