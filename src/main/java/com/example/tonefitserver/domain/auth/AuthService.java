package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.GoogleAuthResponse;
import com.example.tonefitserver.core.dto.auth.TermsAgreementDto;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.TermsType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.JwtTokenProvider;
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
 *
 * <p>웹 데모는 토큰 없이 호출하므로 익명 토큰 발급은 없고, 자체 refresh token 도 폐지됐다 —
 * Extension 은 access token 만료 시 chrome.identity 로 새 Google ID token 을 받아 {@link #googleAuth}
 * 를 재호출(재로그인)해 새 access token 을 얻는다. BE 는 access token 만 발급하는 stateless 인증.
 *
 * <p>로그아웃은 서버 무효화 대상(refresh row)이 사라져 API 가 없다 — FE 가 access token 폐기로 처리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    public static final String PROVIDER_GOOGLE = "GOOGLE";

    private final UserRepository userRepository;
    private final UserTermsAgreementRepository userTermsAgreementRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;

    /**
     * Google ID token 으로 신규 가입 / 로그인을 분기 처리한다.
     *
     * <ul>
     *   <li>provider+sub 매칭되는 정식 user 있음 → 로그인 (200). 필수 약관 미보유면 차단(FUNC-Ag-03 #2)</li>
     *   <li>없음 → 신규 가입 (201, terms_agreements 필수)</li>
     * </ul>
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
        String picture = extractPicture(payload);

        var existingByProvider = userRepository
                .findByProviderAndProviderIdAndStatus(PROVIDER_GOOGLE, providerId, UserStatus.ACTIVE);

        // 기존 정식 로그인
        if (existingByProvider.isPresent()) {
            User existing = existingByProvider.get();
            // FUNC-Ag-03 #2: 필수 약관 보유 여부 확인. 미보유면 access_token 없이 차단 —
            // termsAgreements 함께 오면 즉시 동의 처리 후 진행.
            enforceRequiredTermsForLogin(existing, termsAgreements);
            return new GoogleAuthResult(toBody(existing, picture, issueAccessToken(existing.getId())), false);
        }

        // 신규 정식 가입
        requireTermsAgreement(termsAgreements);
        User created = User.registered(email, PROVIDER_GOOGLE, providerId, name);
        userRepository.save(created);
        saveTermsAgreements(created, termsAgreements);
        return new GoogleAuthResult(toBody(created, picture, issueAccessToken(created.getId())), true);
    }

    /**
     * 기존 정식 사용자 로그인 시 필수 약관 보유 여부 검사.
     * 활성(agreed=true AND revoked_at IS NULL) 기록이 없는 필수 약관이 하나라도 있으면:
     * <ul>
     *   <li>termsAgreements 가 함께 왔으면 → 누락분이 동의로 채워졌는지 검증 후 저장 + 통과</li>
     *   <li>없으면 → {@code TERMS_AGREEMENT_REQUIRED} + payload {@code {"missing_terms": [...]}} 로 차단</li>
     * </ul>
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

        saveTermsAgreements(user, termsAgreements);
    }

    // -----------------------------------------------------------------------
    // 내부 헬퍼
    // -----------------------------------------------------------------------

    /** access token 발급 (stateless). 모든 user 는 정식. */
    private String issueAccessToken(Long userId) {
        return jwtTokenProvider.createAccessToken(userId);
    }

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
     * Google ID token 의 picture(프로필 이미지 URL). 없으면 null.
     * BE 에 저장하지 않고 로그인 응답 body 로만 전달 — FE 가 access token 과 함께 캐시한다
     * (이미지 생애주기 = 토큰 생애주기. /users/me 는 별도로 들고 있지 않음).
     */
    private String extractPicture(GoogleIdToken.Payload payload) {
        Object p = payload.get("picture");
        if (p == null) return null;
        String s = p.toString().trim();
        return s.isEmpty() ? null : s;
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

    private GoogleAuthResponse toBody(User user, String profileImageUrl, String accessToken) {
        return new GoogleAuthResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                profileImageUrl,
                user.getProvider(),
                user.getPlan(),
                user.getCreditBalance(),
                accessToken
        );
    }
}
