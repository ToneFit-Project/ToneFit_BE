package com.example.tonefitserver.domain.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public RefreshToken(String token, Long userId) {
        this.token = token;
        this.userId = userId;
    }

    public void updateToken(String token) {
        this.token = token;
    }
}
