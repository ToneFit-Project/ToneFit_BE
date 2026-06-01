package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.enums.TermsType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자가 가입 시 또는 이후에 동의/거부한 약관 기록. PM 요구사항 REQ-Agree.
 *
 * <p>(user_id, terms_type, version) 단위로 1행. 약관 본문 갱신 시 version 을 올려 새 row 가 적재된다.
 *
 * <p>법적 입증 의무(개인정보보호법 §22)는 "누가/어떤 항목/언제" 까지 기록:
 * <ul>
 *   <li>누가 — {@code user_id}</li>
 *   <li>어떤 항목 — {@code terms_type, version}</li>
 *   <li>언제 — {@code agreed_at}</li>
 * </ul>
 * IP / user-agent 는 PM 확인 결과 수집하지 않는다.
 *
 * <p>선택 동의(MARKETING / AI_LEARNING) 철회 시 {@link #revoke()} 로 {@code revoked_at} 기록
 * (FUNC-Ag-06 — 해당 레코드에 철회 시각 기록). 활성 여부는 {@code agreed=true AND revoked_at IS NULL} 로 판정한다.
 * 같은 (user, type, version) 재동의는 {@link #reactivate()} 로 in-place 갱신 — 동의/철회 전체 이력 보존은
 * 요구사항에 없다.
 */
@Entity
@Table(
        name = "user_terms_agreement",
        uniqueConstraints = @UniqueConstraint(
                name = "user_terms_agreement_uk",
                columnNames = {"user_id", "terms_type", "version"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTermsAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "terms_type", nullable = false)
    private TermsType type;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private boolean agreed;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    /** 선택 동의 철회 시각. null 이면 활성. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    public UserTermsAgreement(User user, TermsType type, String version, boolean agreed) {
        this.user = user;
        this.type = type;
        this.version = version;
        this.agreed = agreed;
        this.agreedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return agreed && revokedAt == null;
    }

    /** 선택 동의 철회. 필수 항목은 호출 전 서비스 계층에서 거부된다. */
    public void revoke() {
        if (this.type.isRequired()) {
            throw new IllegalStateException("필수 약관은 철회할 수 없습니다: " + type);
        }
        this.revokedAt = LocalDateTime.now();
    }

    /** 철회된 동의를 재활성화. version 동일하면 같은 row 갱신, 다르면 별도 row INSERT. */
    public void reactivate() {
        this.revokedAt = null;
        this.agreed = true;
        this.agreedAt = LocalDateTime.now();
    }
}
