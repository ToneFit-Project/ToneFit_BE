package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.auth.RefreshResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.enums.UserStatus;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.JwtTokenProvider;
import com.example.tonefitserver.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * refresh token 발급·회전(RTR)·철회 (V26 재도입).
 *
 * <p>배경: chrome.identity silent 갱신 불가 판명(2026-07) — access(1h) 만료 시마다 interactive
 * 로그인이 강제되어, V15 에서 폐지했던 refresh 를 복원. 저장은 확장 전용 격리 저장소인
 * {@code chrome.storage.local}(FE) — 기존 HttpOnly 쿠키 구조는 웹서비스용이라 채택 안 함.
 *
 * <ul>
 *   <li><b>opaque 토큰</b>: 256bit 랜덤(base64url). 서버는 SHA-256 해시만 보관 — DB 유출에도 재사용 불가.
 *       JWT 가 아니므로 access 자리에 오용될 수 없다(JwtAuthenticationFilter 검증 실패 → 401).</li>
 *   <li><b>RTR</b>: 갱신마다 기존 행 used 처리 + 같은 family 새 행 발급. used/revoked 행이 다시
 *       제시되면 재사용(탈취 신호)으로 family 전체 철회 — FE 는 갱신 호출을 single-flight 로 직렬화할 것.</li>
 *   <li><b>재사용 유예(reuse interval, V28)</b>: 소진 후 유예(기본 15초, {@code jwt.refresh-reuse-grace-seconds})
 *       내 재제시는 갱신 응답 유실 후 재시도로 보고 철회 대신 한 번 더 회전 — single-flight 로도 못 막는
 *       "회전은 됐는데 응답을 못 받은" 케이스의 강제 로그아웃 방지. 유예 창 동안 family 에 활성 토큰이
 *       일시적으로 2개 존재하는 것은 감수.</li>
 *   <li><b>철회</b>: 로그아웃 시 제시 토큰의 family 철회. 미존재 토큰은 조용히 무시(유효성 노출 방지).</li>
 * </ul>
 */
@Slf4j
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final long refreshValidityDays;
    private final long reuseGraceSeconds;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               JwtTokenProvider jwtTokenProvider,
                               @Value("${jwt.refresh-expiration-days}") long refreshValidityDays,
                               @Value("${jwt.refresh-reuse-grace-seconds}") long reuseGraceSeconds) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshValidityDays = refreshValidityDays;
        this.reuseGraceSeconds = reuseGraceSeconds;
    }

    /** 로그인 시 새 family 로 refresh 발급 — 원문 반환(응답 body 전용), 저장은 해시만. */
    @Transactional
    public String issue(Long userId) {
        String raw = generateToken();
        refreshTokenRepository.save(new RefreshToken(
                userId, UUID.randomUUID(), sha256Hex(raw),
                LocalDateTime.now().plusDays(refreshValidityDays)));
        return raw;
    }

    /**
     * 갱신(회전). 유효한 refresh → 새 access + 새 refresh(같은 family). 실패는 전부
     * {@code 401 INVALID_TOKEN} — FE 는 재로그인으로 회수한다.
     */
    @Transactional
    public RefreshResponse rotate(String rawToken) {
        RefreshToken row = refreshTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorType.INVALID_TOKEN, "유효하지 않은 refresh token 입니다."));
        LocalDateTime now = LocalDateTime.now();

        // 철회된 family(로그아웃·재사용 봉쇄 이후) — 유예 없이 거절.
        if (row.getRevokedAt() != null) {
            throw new BusinessException(ErrorType.INVALID_TOKEN, "유효하지 않은 refresh token 입니다.");
        }
        boolean graceReplay = row.isWithinReuseGrace(now, reuseGraceSeconds);
        if (row.isUsed() && !graceReplay) {
            // 재사용 감지 — 탈취(또는 유예를 넘긴 지연 재시도) 신호. family 전체 철회로 봉쇄.
            refreshTokenRepository.revokeFamily(row.getFamilyId(), now);
            log.warn("refresh token reuse detected userId={} familyId={}", row.getUserId(), row.getFamilyId());
            throw new BusinessException(ErrorType.INVALID_TOKEN, "유효하지 않은 refresh token 입니다.");
        }
        if (row.isExpired(now)) {
            throw new BusinessException(ErrorType.INVALID_TOKEN, "만료된 refresh token 입니다.");
        }
        // 비활성 계정 차단 — 계정 정지 시 refresh 로 세션이 연장되지 않도록.
        userRepository.findByIdAndStatus(row.getUserId(), UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorType.INVALID_TOKEN, "유효하지 않은 refresh token 입니다."));

        if (graceReplay) {
            // 갱신 응답 유실 후 재시도 — 철회 대신 한 번 더 회전(reuse interval). 직전 발급본은
            // 해시만 보관이라 재전달이 불가능해 새 토큰을 추가 발급한다. 제시된 행은 이미 used 라 그대로 둠.
            log.info("refresh token replay within grace — re-rotating userId={} familyId={}",
                    row.getUserId(), row.getFamilyId());
        } else {
            row.markUsed(now);
        }
        String newRaw = generateToken();
        refreshTokenRepository.save(new RefreshToken(
                row.getUserId(), row.getFamilyId(), sha256Hex(newRaw), now.plusDays(refreshValidityDays)));
        return new RefreshResponse(jwtTokenProvider.createAccessToken(row.getUserId()), newRaw);
    }

    /** 로그아웃 — 제시 토큰의 family 철회. 미존재 토큰은 조용히 무시(멱등·유효성 비노출). */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .ifPresent(row -> refreshTokenRepository.revokeFamily(row.getFamilyId(), LocalDateTime.now()));
    }

    /** 만료분 파기 — 매일 00:30 (Cleanup 계열과 시간 분산). used/revoked 행도 만료되면 함께 정리된다. */
    @Scheduled(cron = "0 30 0 * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = refreshTokenRepository.deleteExpiredBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("refresh_token purge deleted={}", deleted);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
