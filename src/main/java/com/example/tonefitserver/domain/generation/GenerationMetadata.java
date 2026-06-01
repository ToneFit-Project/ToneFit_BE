package com.example.tonefitserver.domain.generation;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 생성(초안) 호출 메타데이터. PM 요구사항 REQ-Extension FUNC-Ext-11.
 *
 * <p>개인을 식별·재구성할 수 없는 항목만 보존한다 — 수신자 유형/목적/상황 글자수/결과 길이/
 * 소요 시간/성공·실패. 초안 원문(제목·본문)은 저장하지 않는다.
 *
 * <p>AI 학습 활용(AI_LEARNING) 동의자에 한해 INSERT. 90일 후 자동 파기, 철회 시 즉시 삭제.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "generation_metadata")
public class GenerationMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "receiver_type", nullable = false)
    private Receiver receiverType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Column(name = "brief_length", nullable = false)
    private int briefLength;

    /** 생성 결과 본문 길이. 실패 시 null. */
    @Column(name = "result_length")
    private Integer resultLength;

    /** AI 호출 소요 시간(ms). */
    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(nullable = false)
    private boolean success;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private GenerationMetadata(Long userId, Receiver receiverType, Purpose purpose,
                               int briefLength, Integer resultLength, Long durationMs, boolean success) {
        this.userId = userId;
        this.receiverType = receiverType;
        this.purpose = purpose;
        this.briefLength = briefLength;
        this.resultLength = resultLength;
        this.durationMs = durationMs;
        this.success = success;
    }

    public static GenerationMetadata success(Long userId, Receiver receiverType, Purpose purpose,
                                             int briefLength, int resultLength, long durationMs) {
        return new GenerationMetadata(userId, receiverType, purpose, briefLength, resultLength, durationMs, true);
    }

    public static GenerationMetadata failure(Long userId, Receiver receiverType, Purpose purpose,
                                             int briefLength, long durationMs) {
        return new GenerationMetadata(userId, receiverType, purpose, briefLength, null, durationMs, false);
    }
}
