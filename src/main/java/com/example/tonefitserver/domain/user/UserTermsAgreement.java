package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.enums.TermsType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자가 가입 시 또는 이후에 동의/거부한 약관 기록.
 * (user_id, terms_type, version) 단위로 1행. 약관 본문 갱신 시 version 을 올려 새 row 가 적재되게 한다.
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

    public UserTermsAgreement(User user, TermsType type, String version, boolean agreed) {
        this.user = user;
        this.type = type;
        this.version = version;
        this.agreed = agreed;
        this.agreedAt = LocalDateTime.now();
    }
}
