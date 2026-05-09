package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.AnonymousResponse;
import com.example.tonefitserver.core.dto.auth.AuthResponse;
import com.example.tonefitserver.core.dto.auth.LoginRequest;
import com.example.tonefitserver.core.dto.auth.LoginResponse;
import com.example.tonefitserver.core.dto.auth.ReissueRequest;
import com.example.tonefitserver.core.dto.auth.SignupRequest;
import com.example.tonefitserver.core.dto.auth.TokenResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.JwtTokenProvider;
import com.example.tonefitserver.domain.user.RefreshToken;
import com.example.tonefitserver.domain.user.RefreshTokenRepository;
import com.example.tonefitserver.domain.user.User;
import com.example.tonefitserver.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 신규 가입 또는 익명 → 정식 전환 (in-place 승격).
     *
     * <p>currentUserId 가 null 이면 새 user 행을 생성한다.
     * 익명 사용자가 본인 access_token 으로 호출하면 동일 user.id 를 유지한 채 정식으로 승격된다.
     * 이미 정식 가입된 사용자가 호출하면 거부.
     *
     * <p>같은 user.id 를 보존하므로 익명 시기에 만들어진 correction_session·event_log
     * 등 FK 참조는 별도 마이그레이션 없이 자동으로 새 정식 계정 소유로 이어진다.
     *
     * <p>약관 동의 수집은 MVP 에서 제외 (PM 결정). user_terms_agreement 테이블/엔티티는
     * 향후 별도 동의 수집 엔드포인트가 사용할 수 있도록 보존되어 있다.
     */
    @Transactional
    public AuthResponse signup(SignupRequest request, Long currentUserId) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorType.EMAIL_ALREADY_EXISTS);
        }

        User user;
        if (currentUserId != null) {
            User existing = userRepository.findByIdAndStatus(currentUserId, UserStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorType.USER_NOT_FOUND));
            if (!existing.isGuest()) {
                throw new BusinessException(ErrorType.INVALID_REQUEST,
                        "이미 정식 가입된 계정입니다. 로그아웃 후 다시 시도하세요.");
            }
            existing.promote(
                    request.email(),
                    passwordEncoder.encode(request.password()),
                    request.nickname(),
                    request.industry(),
                    request.careerLevel()
            );
            user = existing;
        } else {
            user = User.registered(
                    request.email(),
                    passwordEncoder.encode(request.password()),
                    request.nickname(),
                    request.industry(),
                    request.careerLevel()
            );
            userRepository.save(user);
        }

        TokenResponse tokens = generateAndSaveTokens(user.getId(), false);
        return new AuthResponse(user.getId(), user.getEmail(), false, user.getPlan(),
                tokens.accessToken(), tokens.refreshToken());
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndStatus(request.email(), UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_NOT_FOUND));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorType.INVALID_PASSWORD);
        }

        TokenResponse tokens = generateAndSaveTokens(user.getId(), false);
        return new LoginResponse(user.getId(), user.getEmail(), false, user.getPlan(),
                user.getFreeUsed(), user.getCreditBalance(),
                tokens.accessToken(), tokens.refreshToken());
    }

    /**
     * 익명 사용자 발급. 가입 없이 URL 진입 직후 FE 가 호출한다 (PRD FR-1.5).
     * 매 호출마다 새 anonymous_token 으로 새 user 행 생성.
     */
    @Transactional
    public AnonymousResponse anonymous() {
        String anonymousToken = UUID.randomUUID().toString();
        User user = User.guest(anonymousToken);
        userRepository.save(user);

        TokenResponse tokens = generateAndSaveTokens(user.getId(), true);
        return new AnonymousResponse(user.getId(), true, user.getPlan(),
                anonymousToken, tokens.accessToken(), tokens.refreshToken());
    }

    @Transactional
    public TokenResponse refresh(ReissueRequest request) {
        String refreshTokenString = request.refreshToken();

        if (!jwtTokenProvider.validateToken(refreshTokenString)) {
            throw new BusinessException(ErrorType.INVALID_TOKEN);
        }

        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenString)
                .orElseThrow(() -> new BusinessException(ErrorType.INVALID_TOKEN));

        User user = userRepository.findByIdAndStatus(refreshToken.getUserId(), UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_INACTIVE));

        return generateAndSaveTokens(user.getId(), user.isGuest());
    }

    private TokenResponse generateAndSaveTokens(Long userId, boolean isGuest) {
        String accessToken = jwtTokenProvider.createAccessToken(userId, isGuest);
        String refreshTokenString = jwtTokenProvider.createRefreshToken(userId);

        refreshTokenRepository.findByUserId(userId)
                .ifPresentOrElse(
                        rt -> rt.updateToken(refreshTokenString),
                        () -> refreshTokenRepository.save(new RefreshToken(refreshTokenString, userId))
                );

        return new TokenResponse(accessToken, refreshTokenString);
    }
}
