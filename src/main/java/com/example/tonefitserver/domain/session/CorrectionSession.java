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

/**
 * 교정 세션. v0.5 부터 흐름이 단순화돼 필드도 정리됨.
 *
 * <p>제거: subject / ai_final / ai_subject / user_subject / final_prompt_ver_id /
 * recorrect_count / structure_corrected. v0.4 컬럼은 V11 마이그레이션에서 drop.
 *
 * <p>흐름: POST /corrections (IN_PROGRESS) → 개별 reject 선택 → POST /confirm (CONFIRMED).
 * confirm 시 user_final 채워지고 미처리 changes 는 일괄 ACCEPTED.
 */
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

    /**
     * 이 세션이 사용한 교정 prompt 버전. 추후 prompt 변경 후 회귀 분석용.
     * (Phase 3 에서 PromptPurpose 가 CORRECTION/GENERATION 으로 재정의되면 그쪽 row 참조)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initial_prompt_ver_id")
    private PromptVersion initialPromptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Receiver receiverType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Range> protectedRanges;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(columnDefinition = "text", nullable = false)
    private String original;

    /** 사용자가 송신한 최종본. confirm 시점에 채워짐. */
    @Column(name = "user_final", columnDefinition = "text")
    private String userFinal;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public CorrectionSession(User user, PromptVersion initialPromptVersion,
                             Receiver receiverType, Purpose purpose,
                             List<Range> protectedRanges, Status status, String original) {
        this.user = user;
        this.initialPromptVersion = initialPromptVersion;
        this.receiverType = receiverType;
        this.purpose = purpose;
        this.protectedRanges = protectedRanges;
        this.status = status;
        this.original = original;
    }

    public void updateStatus(Status status) {
        this.status = status;
    }

    public void updateInitialPromptVersion(PromptVersion initialPromptVersion) {
        this.initialPromptVersion = initialPromptVersion;
    }

    /** confirm 시점에 사용자가 실제 송신한 본문 저장. */
    public void confirmWith(String userFinal) {
        this.userFinal = userFinal;
        this.status = Status.CONFIRMED;
    }
}
