package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.enums.TermsType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserTermsAgreementRepository extends JpaRepository<UserTermsAgreement, Long> {

    List<UserTermsAgreement> findByUserId(Long userId);

    Optional<UserTermsAgreement> findByUserIdAndTypeAndVersion(Long userId, TermsType type, String version);

    /**
     * 특정 약관 종류의 활성 동의 row 들 (버전 무관). 정상 상태에선 0 또는 1개지만,
     * 약관 버전업 직후 구버전 row 가 활성으로 남아있는 과도기를 위해 List 로 받는다.
     * toggleTerms 의 철회/동의 판정에 사용 — currentVersion 만 보면 구버전 활성 동의를 놓친다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select a from UserTermsAgreement a
            where a.user.id = :userId and a.type = :type
              and a.agreed = true and a.revokedAt is null
            """)
    List<UserTermsAgreement> findActiveByUserIdAndType(
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("type") TermsType type);

    /**
     * 활성 동의(agreed=true AND revoked_at IS NULL) 가 존재하는 약관 종류 목록.
     * Google OAuth 로그인 시 필수 약관 보유 여부 검사에 사용.
     */
    @org.springframework.data.jpa.repository.Query("""
            select distinct a.type from UserTermsAgreement a
            where a.user.id = :userId
              and a.agreed = true
              and a.revokedAt is null
            """)
    List<TermsType> findActiveTypesByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
