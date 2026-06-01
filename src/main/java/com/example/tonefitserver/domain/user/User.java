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
 * 사용자 엔티티. v0.5 스키마 기준.
 *
 * <p>익명(is_guest=true) 과 정식(is_guest=false) 둘 다 같은 테이블로 관리한다.
 * 정식 사용자는 Google OAuth(향후 다른 provider 확장) 단일 흐름으로만 가입한다.
 * <ul>
 *   <li>익명: anonymous_token 만 채움</li>
 *   <li>정식: email + provider + provider_id + nickname 모두 채움
 *       (nickname 은 Google 프로필 표시 이름 — PM 요구사항 FUNC-Au-02 #2)</li>
 * </ul>
 * DB 측 CHECK constraint 가 둘 중 하나의 형태만 허용한다.
 *
 * <p>생성(Generation) 무료 한도(free_used) 는 PM 결정으로 BE 미관리 →
 * 컬럼·필드·증가 메서드 모두 제거. FE 가 localStorage 로 카운트한다.
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

    @Column(name = "is_guest", nullable = false)
    private boolean isGuest;

    @Column(name = "anonymous_token", unique = true)
    private String anonymousToken;

    @Column(unique = true)
    private String email;

    /** OAuth provider 식별자. 정식 사용자만 채워진다. (예: {@code "GOOGLE"}) */
    @Column(length = 16)
    private String provider;

    /** provider 의 stable user id. Google 의 경우 {@code sub} claim. 정식 사용자만. */
    @Column(name = "provider_id")
    private String providerId;

    /** Google 프로필의 표시 이름. 정식 사용자만 채워진다. */
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
        u.isGuest = false;
        u.email = email;
        u.provider = provider;
        u.providerId = providerId;
        u.nickname = nickname;
        u.plan = Plan.FREE;
        u.status = UserStatus.ACTIVE;
        u.creditBalance = 0;
        return u;
    }

    /** 익명 사용자 생성. anonymousToken 만 채운다. */
    public static User guest(String anonymousToken) {
        User u = new User();
        u.isGuest = true;
        u.anonymousToken = anonymousToken;
        u.plan = Plan.FREE;
        u.status = UserStatus.ACTIVE;
        u.creditBalance = 0;
        return u;
    }

    public void deactivate() {
        this.status = UserStatus.INACTIVE;
    }

    /**
     * 익명 사용자를 정식 가입자로 승격한다. user.id 는 보존되므로
     * 모든 FK(correction_session, correction_feedback, event_log)도 그대로 유효하다.
     * — 별도 데이터 마이그레이션 불필요.
     */
    public void promote(String email, String provider, String providerId, String nickname) {
        if (!this.isGuest) {
            throw new IllegalStateException("이미 정식 가입된 사용자입니다.");
        }
        this.isGuest = false;
        this.anonymousToken = null;
        this.email = email;
        this.provider = provider;
        this.providerId = providerId;
        this.nickname = nickname;
    }
}
