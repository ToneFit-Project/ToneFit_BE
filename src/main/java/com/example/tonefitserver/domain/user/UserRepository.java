package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByIdAndStatus(Long id, UserStatus status);

    /**
     * OAuth provider 의 stable user id 로 정식 사용자 조회.
     * Google 의 경우 ID token 의 {@code sub} claim 이 providerId 다.
     */
    Optional<User> findByProviderAndProviderIdAndStatus(String provider, String providerId, UserStatus status);
}
