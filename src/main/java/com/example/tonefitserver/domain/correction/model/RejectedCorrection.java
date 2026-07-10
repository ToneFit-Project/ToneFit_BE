package com.example.tonefitserver.domain.correction.model;

import com.example.tonefitserver.core.enums.Receiver;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 사용자가 거절한 교정 항목. PM 요구사항 REQ-Correction FUNC-Cor-06.
 *
 * <p>전체 원문·확정본·수락 항목은 저장하지 않는다. 거절한 항목 묶음(원문 구절 + AI 제안 교정문 +
 * '의미 훼손 의심' 플래그(표시한 경우))만 보존한다. 전체 메일을 복원할 수 없도록 구절만 따로 저장하며
 * <b>위치 오프셋은 보관하지 않는다</b>(FUNC-Cor-06).
 *
 * <p>AI 학습 활용(AI_LEARNING) 동의자에 한해 INSERT. 90일 후 자동 파기, 철회 시 즉시 삭제.
 * 용도: PM 수동 분석(과교정 줄이기·프롬프트 개선) 한정.
 *
 * <p>측정(event_log / Amplitude, ANALYTICS 약관)과는 별개 — 보존정책·약관기준·목적이 다르므로 분리.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "rejected_correction")
public class RejectedCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "receiver_type", nullable = false)
    private Receiver receiverType;

    /** 교정 계층(필수/권장/선택). 어떤 계층의 교정이 거절됐는지 — 과교정 분석 신호. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Label label;

    /** 거절된 원문 구절. */
    @Column(name = "original_phrase", columnDefinition = "text", nullable = false)
    private String originalPhrase;

    /** AI 가 제안했으나 거절된 교정문. */
    @Column(name = "corrected_phrase", columnDefinition = "text", nullable = false)
    private String correctedPhrase;

    /**
     * '의미 훼손 의심' 선택적 플래그 (FUNC-Cor-02 — AI 가 뜻을 바꿨다는 신호).
     * 사용자가 표시한 경우에만 true/false 값이 있고, 표시하지 않았으면 null (거부 자체는 사유 없이 처리됨).
     */
    @Column(name = "meaning_damage_suspected")
    private Boolean meaningDamageSuspected;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private RejectedCorrection(Long userId, Receiver receiverType, Label label,
                              String originalPhrase, String correctedPhrase, Boolean meaningDamageSuspected) {
        this.userId = userId;
        this.receiverType = receiverType;
        this.label = label;
        this.originalPhrase = originalPhrase;
        this.correctedPhrase = correctedPhrase;
        this.meaningDamageSuspected = meaningDamageSuspected;
    }

    public static RejectedCorrection of(Long userId, Receiver receiverType, Label label,
                                        String originalPhrase, String correctedPhrase,
                                        Boolean meaningDamageSuspected) {
        return new RejectedCorrection(userId, receiverType, label,
                originalPhrase, correctedPhrase, meaningDamageSuspected);
    }
}
