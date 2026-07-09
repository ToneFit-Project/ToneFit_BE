package com.example.tonefitserver.domain.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** family 일괄 철회 — 재사용 감지·로그아웃. 이미 철회된 행은 그대로 둔다. */
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now where r.familyId = :familyId and r.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") LocalDateTime now);

    /** 만료분 파기 (매일 새벽 @Scheduled). 삭제된 row 수 반환. */
    @Modifying
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
