package com.example.tonefitserver.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * refresh token 행 (V26, RTR). 원문은 저장하지 않고 SHA-256 해시만 보관한다.
 *
 * <p>로그인 1회 = family 1개. 회전(rotate) 시 기존 행을 {@code used} 처리하고 같은 family 로
 * 새 행을 만든다. used/revoked 행이 다시 제시되면 재사용(탈취 신호) — family 전체 철회.
 *
 * <p>user 연관 대신 {@code userId} 만 든다 — 인증 경로 전용 조회라 조인 불필요.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private boolean used;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public RefreshToken(Long userId, UUID familyId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.used = false;
        this.expiresAt = expiresAt;
        this.createdAt = LocalDateTime.now();
    }

    /** 회전으로 소진 — 이후 이 행이 다시 제시되면 재사용 신호. */
    public void markUsed() {
        this.used = true;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** 재사용·철회 상태 — 다시 제시되면 family 전체 철회 대상. */
    public boolean isConsumedOrRevoked() {
        return used || revokedAt != null;
    }
}
