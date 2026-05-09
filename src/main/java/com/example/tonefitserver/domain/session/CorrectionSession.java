package com.example.tonefitserver.domain.session;

import com.example.tonefitserver.domain.prompt.PromptVersion;
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
@Table(name = "correction_session")
public class CorrectionSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initial_prompt_ver_id")
    private PromptVersion initialPromptVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "final_prompt_ver_id")
    private PromptVersion finalPromptVersion;

    @Enumerated(EnumType.STRING)
    private Receiver receiverType;

    @Enumerated(EnumType.STRING)
    private Purpose purpose;

    private String subject;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Range> protectedRanges;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(columnDefinition = "text")
    private String original;

    @Column(name = "ai_final", columnDefinition = "text")
    private String aiFinal;

    @Column(name = "user_final", columnDefinition = "text")
    private String userFinal;

    @Column(name = "ai_subject")
    private String aiSubject;

    @Column(name = "user_subject")
    private String userSubject;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public CorrectionSession(User user, PromptVersion initialPromptVersion, PromptVersion finalPromptVersion,
                             Receiver receiverType, Purpose purpose, String subject,
                             List<Range> protectedRanges, Status status, String original) {
        this.user = user;
        this.initialPromptVersion = initialPromptVersion;
        this.finalPromptVersion = finalPromptVersion;
        this.receiverType = receiverType;
        this.purpose = purpose;
        this.subject = subject;
        this.protectedRanges = protectedRanges;
        this.status = status;
        this.original = original;
    }

    public void updateStatus(Status status) {
        this.status = status;
    }

    public void updateDraft(Receiver receiverType, Purpose purpose, String subject, String original) {
        this.receiverType = receiverType;
        this.purpose = purpose;
        this.subject = subject;
        this.original = original;
    }

    public void updateReceiverPurpose(Receiver receiverType, Purpose purpose) {
        if (receiverType != null) this.receiverType = receiverType;
        if (purpose != null) this.purpose = purpose;
    }

    public void updateProtectedRanges(List<Range> protectedRanges) {
        this.protectedRanges = protectedRanges;
    }

    public void updateInitialPromptVersion(PromptVersion initialPromptVersion) {
        this.initialPromptVersion = initialPromptVersion;
    }

    public void updateFinalPromptVersion(PromptVersion finalPromptVersion) {
        this.finalPromptVersion = finalPromptVersion;
    }

    public void updateAiResult(String aiFinal, String aiSubject) {
        this.aiFinal = aiFinal;
        this.aiSubject = aiSubject;
    }

    public void updateUserEdit(String userFinal, String userSubject) {
        if (userFinal != null) this.userFinal = userFinal;
        if (userSubject != null) this.userSubject = userSubject;
    }
}
