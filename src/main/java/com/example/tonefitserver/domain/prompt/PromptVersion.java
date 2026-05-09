package com.example.tonefitserver.domain.prompt;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "prompt_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_prompt_version_purpose_version",
                columnNames = {"purpose", "version"}
        )
)
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromptPurpose purpose;

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
