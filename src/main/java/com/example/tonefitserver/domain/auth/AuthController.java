package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.dto.auth.GoogleAuthRequest;
import com.example.tonefitserver.core.dto.auth.GoogleAuthResponse;
import com.example.tonefitserver.core.dto.auth.RefreshRequest;
import com.example.tonefitserver.core.dto.auth.RefreshResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 컨트롤러. Google OAuth 진입 + refresh 갱신/로그아웃 (RTR 재도입, V26).
 *
 * <ul>
 *   <li>웹 데모: 토큰 없이 {@code /generations} 직접 호출</li>
 *   <li>Extension: {@code /auth/google} 로그인 → access(1h)+refresh 를 body 로 수령,
 *       {@code chrome.storage.local} 보관. access 만료(401) 시 {@code /auth/refresh} 로 silent 갱신 —
 *       chrome.identity silent 갱신 불가 판명(2026-07)에 따른 재도입. refresh 까지 401 이면 interactive 재로그인</li>
 *   <li>로그아웃: {@code /auth/logout} 에 refresh 제출 → family 철회 + FE 토큰 폐기</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    /**
     * Google OAuth — 로그인 / 신규 가입. 신규 가입은 201, 기존 로그인은 200.
     * access + refresh 모두 응답 body 로 발급.
     */
    @PostMapping("/google")
    public ApiResponse<GoogleAuthResponse> google(@RequestBody @Valid GoogleAuthRequest request,
                                                  HttpServletResponse response) {
        GoogleAuthResult result = authService.googleAuth(request.idToken(), request.termsAgreements());
        response.setStatus(result.newUser() ? HttpStatus.CREATED.value() : HttpStatus.OK.value());
        return ApiResponse.success(result.body());
    }

    /**
     * 토큰 갱신 (RTR) — 유효한 refresh 제출 시 새 access + 회전된 새 refresh.
     * 무효·만료·재사용은 전부 401 INVALID_TOKEN — FE 는 interactive 재로그인으로 회수.
     */
    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(@RequestBody @Valid RefreshRequest request) {
        return ApiResponse.success(refreshTokenService.rotate(request.refreshToken()));
    }

    /** 로그아웃 — 제시한 refresh 의 family 철회. 멱등(무효 토큰도 204). */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @Valid RefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }
}
