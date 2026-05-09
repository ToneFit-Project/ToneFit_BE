package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.enums.CareerLevel;
import com.example.tonefitserver.core.enums.Industry;
import com.example.tonefitserver.core.enums.Plan;
import com.example.tonefitserver.core.enums.UserStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 사용자 엔티티. v0.4 스키마 기준.
 *
 * <p>익명(is_guest=true) 과 정식(is_guest=false) 둘 다 같은 테이블로 관리한다.
 * 익명은 anonymous_token 만, 정식은 email/password_hash/nickname 이 NOT NULL.
 * DB 측 CHECK constraint 가 둘 중 하나의 형태만 허용한다.
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

    @Column(name = "password_hash")
    private String passwordHash;

    private String nickname;

    @Enumerated(EnumType.STRING)
    private Industry industry;

    @Enumerated(EnumType.STRING)
    @Column(name = "career_level")
    private CareerLevel careerLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan;

    @Column(name = "free_used", nullable = false)
    private int freeUsed;

    @Column(name = "credit_balance", nullable = false)
    private int creditBalance;

    @Column(name = "last_used")
    private LocalDate lastUsed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 정식 가입자 생성. nickname 은 필수, industry/careerLevel 은 선택. */
    public static User registered(String email, String passwordHash, String nickname,
                                  Industry industry, CareerLevel careerLevel) {
        User u = new User();
        u.isGuest = false;
        u.email = email;
        u.passwordHash = passwordHash;
        u.nickname = nickname;
        u.industry = industry;
        u.careerLevel = careerLevel;
        u.plan = Plan.FREE;
        u.status = UserStatus.ACTIVE;
        u.freeUsed = 0;
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
        u.freeUsed = 0;
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
    public void promote(String email, String passwordHash, String nickname,
                        Industry industry, CareerLevel careerLevel) {
        if (!this.isGuest) {
            throw new IllegalStateException("이미 정식 가입된 사용자입니다.");
        }
        this.isGuest = false;
        this.anonymousToken = null;
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.industry = industry;
        this.careerLevel = careerLevel;
    }

    public void updateProfile(Industry industry, CareerLevel careerLevel) {
        if (industry != null) this.industry = industry;
        if (careerLevel != null) this.careerLevel = careerLevel;
    }

    /** 30일 무활동 정리 배치 기준이 되는 마지막 활동 시점. */
    public void touch() {
        this.lastUsed = LocalDate.now();
    }
}
