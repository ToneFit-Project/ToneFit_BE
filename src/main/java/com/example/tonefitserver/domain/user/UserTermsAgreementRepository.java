package com.example.tonefitserver.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserTermsAgreementRepository extends JpaRepository<UserTermsAgreement, Long> {
    List<UserTermsAgreement> findByUserId(Long userId);
}
