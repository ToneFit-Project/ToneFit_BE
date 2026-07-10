package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.RefreshResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.JwtTokenProvider;
import com.example.tonefitserver.domain.user.User;
import com.example.tonefitserver.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RefreshTokenService 단위 테스트 — 발급·회전(RTR)·재사용 감지·철회. Spring/DB 불필요.
 */
class RefreshTokenServiceTest {

    private RefreshTokenRepository repository;
    private UserRepository userRepository;
    private JwtTokenProvider jwtTokenProvider;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        service = new RefreshTokenService(repository, userRepository, jwtTokenProvider, 14, 15);
    }

    /** 유예 0초 — 모든 재사용이 즉시 철회되는 구성 (재사용 봉쇄 검증용). */
    private RefreshTokenService noGraceService() {
        return new RefreshTokenService(repository, userRepository, jwtTokenProvider, 14, 0);
    }

    private RefreshToken activeRow(Long userId) {
        return new RefreshToken(userId, UUID.randomUUID(), "hash", LocalDateTime.now().plusDays(7));
    }

    @Test
    @DisplayName("발급 — 원문을 반환하고 저장은 해시(원문과 다른 64자 hex)로 한다")
    void issueStoresHashNotRaw() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);

        String raw = service.issue(293L);

        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(raw).isNotBlank();
        assertThat(saved.getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(saved.getUserId()).isEqualTo(293L);
        assertThat(saved.getFamilyId()).isNotNull();
    }

    @Test
    @DisplayName("회전 — 기존 행 used 처리 + 같은 family 새 행 + 새 access/refresh 반환")
    void rotateIssuesNewPairInSameFamily() {
        RefreshToken row = activeRow(293L);
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(row));
        when(userRepository.findByIdAndStatus(293L, UserStatus.ACTIVE)).thenReturn(Optional.of(mock(User.class)));
        when(jwtTokenProvider.createAccessToken(293L)).thenReturn("new-access");

        RefreshResponse result = service.rotate("raw-token");

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(row.isUsed()).isTrue();   // 기존 행 소진

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFamilyId()).isEqualTo(row.getFamilyId());
    }

    @Test
    @DisplayName("재사용 감지(유예 밖) — used 행이 다시 제시되면 family 전체 철회 + 401")
    void reuseRevokesWholeFamily() {
        RefreshToken row = activeRow(293L);
        row.markUsed(LocalDateTime.now());
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> noGraceService().rotate("stolen"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorType()).isEqualTo(ErrorType.INVALID_TOKEN));
        verify(repository).revokeFamily(eq(row.getFamilyId()), any(LocalDateTime.class));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("유예 내 재제시(응답 유실 재시도) — 철회 없이 같은 family 로 한 번 더 회전")
    void replayWithinGraceReRotates() {
        RefreshToken row = activeRow(293L);
        row.markUsed(LocalDateTime.now());   // 방금 소진 — 기본 유예 15초 내
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(row));
        when(userRepository.findByIdAndStatus(293L, UserStatus.ACTIVE)).thenReturn(Optional.of(mock(User.class)));
        when(jwtTokenProvider.createAccessToken(293L)).thenReturn("replay-access");

        RefreshResponse result = service.rotate("lost-response-retry");

        assertThat(result.accessToken()).isEqualTo("replay-access");
        assertThat(result.refreshToken()).isNotBlank();
        verify(repository, never()).revokeFamily(any(), any());
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getFamilyId()).isEqualTo(row.getFamilyId());
    }

    @Test
    @DisplayName("만료 refresh → 401, family 는 유지(재사용 아님)")
    void expiredTokenRejected() {
        RefreshToken row = new RefreshToken(293L, UUID.randomUUID(), "hash", LocalDateTime.now().minusMinutes(1));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.rotate("expired"))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).revokeFamily(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("미존재 토큰 → 401")
    void unknownTokenRejected() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("garbage"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("비활성 계정의 refresh → 401 (세션 연장 차단)")
    void inactiveUserRejected() {
        RefreshToken row = activeRow(293L);
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(row));
        when(userRepository.findByIdAndStatus(293L, UserStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("로그아웃 — 존재하는 토큰은 family 철회, 미존재 토큰은 조용히 무시(멱등)")
    void revokeIsIdempotent() {
        RefreshToken row = activeRow(293L);
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(row)).thenReturn(Optional.empty());

        service.revoke("known");
        verify(repository).revokeFamily(eq(row.getFamilyId()), any(LocalDateTime.class));

        service.revoke("unknown");   // 예외 없이 통과
    }
}
