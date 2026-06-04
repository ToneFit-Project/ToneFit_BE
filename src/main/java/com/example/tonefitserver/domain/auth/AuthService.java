package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.GoogleAuthResponse;
import com.example.tonefitserver.core.dto.auth.TermsAgreementDto;
import com.example.tonefitserver.core.dto.auth.TokenResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.TermsType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.JwtTokenProvider;
import com.example.tonefitserver.domain.user.RefreshToken;
import com.example.tonefitserver.domain.user.RefreshTokenRepository;
import com.example.tonefitserver.domain.user.User;
import com.example.tonefitserver.domain.user.UserRepository;
import com.example.tonefitserver.domain.user.UserTermsAgreement;
import com.example.tonefitserver.domain.user.UserTermsAgreementRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 인증 도메인 서비스. 정식 사용자는 Google OAuth 단일 흐름으로만 진입.
 * 웹 데모는 토큰 없이 호출하므로 익명 토큰/유저 발급은 제거됨.
 *
 * <p>제공 메서드:
 * <ul>
 *   <li>{@link #googleAuth(String, List)} — Google ID token 검증 후 신규 가입 / 로그인 분기</li>
 *   <li>{@link #refresh(String)} — refresh token 으로 access token 재발급(rotation)</li>
 *   <li>{@link #logout(Long)} — 서버측 refresh token 삭제 (단일 디바이스 정책으로 row 1개)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    public static final String PROVIDER_GOOGLE = "GOOGLE";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;

    /**
     * Google ID token 으로 신규 가입 / 로그인을 분기 처리한다.
     *
     * <p>분기 규칙 (PM 요구사항 REQ-OAuth/REQ-Agree):
     * <ul>
     *   <li>provider+sub 매칭되는 정식 user 있음 → 로그인 (200). 단 필수 약관 미보유면 차단(FUNC-Ag-03 #2)</li>
     *   <li>없음 → 신규 가입 (201, terms_agreements 필수)</li>
     * </ul>
     *
     * <p>익명→정식 승격(promotion)은 익명 토큰 폐지로 제거됐다. 웹은 토큰 없이 데모만 쓰고,
     * Extension 은 처음부터 Google OAuth 로 진입하므로 승격 경로 자체가 없다.
     *
     * <p>nickname 은 Google ID token 의 {@code name} claim 에서 가져온다 (FUNC-Au-02 #2).
     */
    @Transactional
    public GoogleAuthResult googleAuth(String idTokenString, List<TermsAgreementDto> termsAgreements) {
        GoogleIdToken.Payload payload = verifyIdToken(idTokenString);
        String providerId = payload.getSubject();
        String email = payload.getEmail();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorType.INVALID_ID_TOKEN, "Google 계정에서 이메일을 가져올 수 없습니다.");
        }
        String name = extractName(payload, email);

        var existingByProvider = userRepository
                .findByProviderAndProviderIdAndStatus(PROVIDER_GOOGLE, providerId, UserStatus.ACTIVE);

        // 기존 정식 로그인
        if (existingByProvider.isPresent()) {
            User existing = existingByProvider.get();
            // FUNC-Ag-03 #2: 필수 약관 보유 여부 확인. 미보유면 access_token 없이 차단 —
            // termsAgreements 함께 오면 즉시 동의 처리 후 진행.
            enforceRequiredTermsForLogin(existing, termsAgreements);
            TokenResponse tokens = generateAndSaveTokens(existing.getId());
            return new GoogleAuthResult(toBody(existing, tokens.accessToken()), tokens.refreshToken(), false);
        }

        // 신규 정식 가입
        requireTermsAgreement(termsAgreements);
        User created = User.registered(email, PROVIDER_GOOGLE, providerId, name);
        userRepository.save(created);
        saveTermsAgreements(created, termsAgreements);
        TokenResponse tokens = generateAndSaveTokens(created.getId());
        return new GoogleAuthResult(toBody(created, tokens.accessToken()), tokens.refreshToken(), true);
    }

    /**
     * 기존 정식 사용자 로그인 시 필수 약관 보유 여부 검사.
     * 활성(agreed=true AND revoked_at IS NULL) 기록이 없는 필수 약관이 하나라도 있으면:
     * <ul>
     *   <li>termsAgreements 가 함께 왔으면 → 누락분이 동의로 채워졌는지 검증 후 저장 + 통과</li>
     *   <li>없으면 → {@code TERMS_AGREEMENT_REQUIRED} + payload {@code {"missing_terms": [...]}} 로 차단</li>
     * </ul>
     * FE 는 응답의 missing_terms 를 보고 약관 화면을 띄워 사용자가 동의 후 같은 엔드포인트에
     * termsAgreements 를 함께 재호출하면 access_token 을 발급받는다.
     */
    private void enforceRequiredTermsForLogin(User user, List<TermsAgreementDto> termsAgreements) {
        Set<TermsType> active = EnumSet.noneOf(TermsType.class);
        active.addAll(userTermsAgreementRepository.findActiveTypesByUserId(user.getId()));

        Set<TermsType> missing = EnumSet.noneOf(TermsType.class);
        for (TermsType t : TermsType.values()) {
            if (t.isRequired() && !active.contains(t)) missing.add(t);
        }
        if (missing.isEmpty()) return;

        if (termsAgreements == null || termsAgreements.isEmpty()) {
            throw new BusinessException(ErrorType.TERMS_AGREEMENT_REQUIRED)
                    .withDetails(java.util.Map.of("missing_terms", missing.stream().map(Enum::name).toList()));
        }

        // 함께 온 termsAgreements 중 missing 항목이 모두 agreed=true 로 채워졌는지 확인.
        Set<TermsType> agreedNow = EnumSet.noneOf(TermsType.class);
        for (TermsAgreementDto dto : termsAgreements) {
            if (Boolean.TRUE.equals(dto.agreed())) agreedNow.add(dto.type());
        }
        Set<TermsType> stillMissing = EnumSet.copyOf(missing);
        stillMissing.removeAll(agreedNow);
        if (!stillMissing.isEmpty()) {
            throw new BusinessException(ErrorType.TERMS_AGREEMENT_REQUIRED)
                    .withDetails(java.util.Map.of("missing_terms", stillMissing.stream().map(Enum::name).toList()));
        }

        // 누락분(또는 함께 온 선택 항목까지) 저장.
        saveTermsAgreements(user, termsAgreements);
    }

    @Transactional
    public TokenResponse refresh(String refreshTokenString) {
        if (refreshTokenString == null || refreshTokenString.isBlank()) {
            throw new BusinessException(ErrorType.INVALID_TOKEN);
        }
        if (!jwtTokenProvider.validateToken(refreshTokenString)) {
            throw new BusinessException(ErrorType.INVALID_TOKEN);
        }
        if (!JwtTokenProvider.TYPE_REFRESH.equals(jwtTokenProvider.getType(refreshTokenString))) {
            throw new BusinessException(ErrorType.INVALID_TOKEN);
        }

        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenString)
                .orElseThrow(() -> new BusinessException(ErrorType.INVALID_TOKEN));

        User user = userRepository.findByIdAndStatus(refreshToken.getUserId(), UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.USER_INACTIVE));

        return generateAndSaveTokens(user.getId());
    }

    /**
     * 서버측 refresh token row 삭제. 단일 디바이스 정책이라 user 당 row 1개.
     * 쿠키 만료(클라이언트측)는 Controller 가 Set-Cookie 헤더로 처리.
     */
    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.findByUserId(userId).ifPresent(refreshTokenRepository::delete);
    }

    // -----------------------------------------------------------------------
    // 내부 헬퍼
    // -----------------------------------------------------------------------

    private GoogleIdToken.Payload verifyIdToken(String idTokenString) {
        try {
            GoogleIdToken idToken = googleIdTokenVerifier.verify(idTokenString);
            if (idToken == null) {
                throw new BusinessException(ErrorType.INVALID_ID_TOKEN);
            }
            return idToken.getPayload();
        } catch (GeneralSecurityException | java.io.IOException e) {
            log.warn("Google ID token 검증 실패: {}", e.getMessage());
            throw new BusinessException(ErrorType.INVALID_ID_TOKEN);
        }
    }

    /**
     * Google ID token payload 의 표시 이름. name 이 비어있으면 email local-part 로 fallback,
     * 그것도 비어있으면 "사용자".
     */
    private String extractName(GoogleIdToken.Payload payload, String email) {
        Object n = payload.get("name");
        if (n != null) {
            String s = n.toString().trim();
            if (!s.isEmpty()) {
                return s.length() > 64 ? s.substring(0, 64) : s;
            }
        }
        int at = email.indexOf('@');
        if (at > 0) return email.substring(0, Math.min(at, 64));
        return "사용자";
    }

    /**
     * 필수 약관(REQUIRED=true) 누락 또는 미동의 시 TERMS_AGREEMENT_REQUIRED.
     * payload {@code {"missing_terms": [...]}} 동봉 — FE 가 어떤 항목을 띄울지 알 수 있도록.
     */
    private void requireTermsAgreement(List<TermsAgreementDto> termsAgreements) {
        Set<TermsType> requiredTypes = EnumSet.noneOf(TermsType.class);
        for (TermsType t : TermsType.values()) {
            if (t.isRequired()) requiredTypes.add(t);
        }

        Set<TermsType> agreedRequired = EnumSet.noneOf(TermsType.class);
        if (termsAgreements != null) {
            for (TermsAgreementDto dto : termsAgreements) {
                if (Boolean.TRUE.equals(dto.agreed()) && requiredTypes.contains(dto.type())) {
                    agreedRequired.add(dto.type());
                }
            }
        }

        Set<TermsType> missing = EnumSet.copyOf(requiredTypes);
        missing.removeAll(agreedRequired);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorType.TERMS_AGREEMENT_REQUIRED)
                    .withDetails(java.util.Map.of("missing_terms", missing.stream().map(Enum::name).toList()));
        }
    }

    /**
     * 약관 동의 저장 — 멱등. 같은 (user, type, version) 이 이미 있으면 INSERT 하지 않는다.
     * 신규 가입은 충돌이 없지만, 재로그인 재동의 흐름에선 사용자가 이미 동의한 항목까지 함께 재전송될 수 있어
     * 무조건 INSERT 하면 user_terms_agreement_uk(user_id, terms_type, version) UNIQUE 위반 → 500 이 된다.
     * 기존 row 가 철회 상태인데 다시 동의로 오면 reactivate.
     */
    private void saveTermsAgreements(User user, List<TermsAgreementDto> termsAgreements) {
        if (termsAgreements == null) return;
        for (TermsAgreementDto dto : termsAgreements) {
            boolean agreed = Boolean.TRUE.equals(dto.agreed());
            userTermsAgreementRepository
                    .findByUserIdAndTypeAndVersion(user.getId(), dto.type(), dto.version())
                    .ifPresentOrElse(
                            existing -> {
                                if (agreed && !existing.isActive()) existing.reactivate();
                            },
                            () -> userTermsAgreementRepository.save(
                                    new UserTermsAgreement(user, dto.type(), dto.version(), agreed)));
        }
    }

    private GoogleAuthResponse toBody(User user, String accessToken) {
        return new GoogleAuthResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProvider(),
                user.isGuest(),
                user.getPlan(),
                user.getCreditBalance(),
                accessToken
        );
    }

    /**
     * access/refresh 토큰 발급 + refresh row upsert. 익명 토큰 폐지로 전부 정식(is_guest=false).
     * (JWT is_guest claim·refresh 만료 분기는 후속 정리에서 제거 예정 — 현재는 false 고정으로 동작)
     */
    private TokenResponse generateAndSaveTokens(Long userId) {
        String accessToken = jwtTokenProvider.createAccessToken(userId, false);
        String refreshTokenString = jwtTokenProvider.createRefreshToken(userId, false);

        refreshTokenRepository.findByUserId(userId)
                .ifPresentOrElse(
                        rt -> rt.updateToken(refreshTokenString),
                        () -> refreshTokenRepository.save(new RefreshToken(refreshTokenString, userId))
                );

        return new TokenResponse(accessToken, refreshTokenString, false);
    }
}
