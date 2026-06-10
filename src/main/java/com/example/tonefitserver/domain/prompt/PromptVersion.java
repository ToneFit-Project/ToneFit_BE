package com.example.tonefitserver.domain.prompt;

import com.example.tonefitserver.core.enums.Receiver;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Prompt 버전. v0.5 부터 (purpose, recipient_type) 조합당 활성 1개.
 *
 * <p>partial UNIQUE: {@code (purpose, recipient_type) WHERE is_active = true} — V12 마이그레이션.
 * 전체 UNIQUE: {@code (purpose, recipient_type, version)} — JPA 측에서도 동일하게 선언.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "prompt_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_prompt_version_purpose_recipient_version",
                columnNames = {"purpose", "recipient_type", "version"}
        )
)
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromptPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false)
    private Receiver recipientType;

    @Column(nullable = false)
    private String version;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
