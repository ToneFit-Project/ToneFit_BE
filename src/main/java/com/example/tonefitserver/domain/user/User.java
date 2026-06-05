package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.enums.Plan;
import com.example.tonefitserver.core.enums.UserStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 사용자 엔티티. 정식 사용자(Google OAuth)만 존재한다.
 *
 * <p>익명 토큰/유저 폐지로 {@code is_guest}·{@code anonymous_token} 컬럼은 제거됨(V16).
 * 모든 user 는 email + provider + provider_id + nickname 을 가진다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email;

    /** OAuth provider 식별자. (예: {@code "GOOGLE"}) */
    @Column(length = 16)
    private String provider;

    /** provider 의 stable user id. Google 의 경우 {@code sub} claim. */
    @Column(name = "provider_id")
    private String providerId;

    /** Google 프로필의 표시 이름. */
    @Column(length = 64)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Column(name = "credit_balance", nullable = false)
    private int creditBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 정식 가입자 생성 — OAuth provider 정보 + nickname 필수. */
    public static User registered(String email, String provider, String providerId, String nickname) {
        User u = new User();
        u.email = email;
        u.provider = provider;
        u.providerId = providerId;
        u.nickname = nickname;
        u.plan = Plan.FREE;
        u.status = UserStatus.ACTIVE;
        u.creditBalance = 0;
        return u;
    }

    public void deactivate() {
        this.status = UserStatus.INACTIVE;
    }
}
