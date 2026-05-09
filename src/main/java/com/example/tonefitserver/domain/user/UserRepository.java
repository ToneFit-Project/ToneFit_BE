package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmailAndStatus(String email, UserStatus status);
    Optional<User> findByIdAndStatus(Long id, UserStatus status);
    boolean existsByEmail(String email);
}
