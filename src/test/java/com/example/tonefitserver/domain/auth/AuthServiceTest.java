package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.JwtTokenProvider;
import com.example.tonefitserver.domain.user.UserRepository;
import com.example.tonefitserver.domain.user.UserTermsAgreementRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuthService.verifyIdToken 의 예외 매핑 단위 테스트 — Spring/Docker 불필요.
 *
 * <p>googleAuth 진입 직후 토큰 검증 단계에서 예외가 나므로 verifier 외 의존성은 호출되지 않는다.
 */
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserTermsAgreementRepository termsRepository = mock(UserTermsAgreementRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

    private final AuthService authService =
            new AuthService(userRepository, termsRepository, jwtTokenProvider, verifier, refreshTokenService);

    @Test
    @DisplayName("형식이 깨진 ID token(파싱 단계 IllegalArgumentException)도 500 이 아닌 INVALID_ID_TOKEN")
    void malformedIdTokenMapsToInvalidIdToken() throws Exception {
        // Google client 는 세그먼트 수·base64 오류 시 IllegalArgumentException 을 던진다.
        when(verifier.verify(anyString()))
                .thenThrow(new IllegalArgumentException("Wrong number of segments in token"));

        assertThatThrownBy(() -> authService.googleAuth("not.a.valid.jwt", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorType())
                .isEqualTo(ErrorType.INVALID_ID_TOKEN);
    }

    @Test
    @DisplayName("서명·만료 검증 실패(GeneralSecurityException)는 INVALID_ID_TOKEN")
    void securityExceptionMapsToInvalidIdToken() throws Exception {
        when(verifier.verify(anyString())).thenThrow(new GeneralSecurityException("bad signature"));

        assertThatThrownBy(() -> authService.googleAuth("a.b.c", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorType())
                .isEqualTo(ErrorType.INVALID_ID_TOKEN);
    }

    @Test
    @DisplayName("verify 가 null 반환(검증 미통과)이면 INVALID_ID_TOKEN")
    void nullVerificationMapsToInvalidIdToken() throws Exception {
        when(verifier.verify(anyString())).thenReturn(null);

        BusinessException ex = (BusinessException) org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class, () -> authService.googleAuth("x.y.z", null));
        assertThat(ex.getErrorType()).isEqualTo(ErrorType.INVALID_ID_TOKEN);
    }
}
