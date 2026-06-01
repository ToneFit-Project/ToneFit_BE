package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.dto.auth.AnonymousResponse;
import com.example.tonefitserver.core.dto.auth.GoogleAuthRequest;
import com.example.tonefitserver.core.dto.auth.GoogleAuthResponse;
import com.example.tonefitserver.core.dto.auth.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    /**
     * 익명 토큰 발급. 모든 진입자가 최초 호출.
     * refresh token 은 HttpOnly Cookie 로 발급 (Max-Age = 7일).
     */
    @PostMapping("/anonymous")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AnonymousResponse> anonymous(HttpServletResponse response) {
        AnonymousResult result = authService.anonymous();
        response.addHeader(refreshTokenCookie.headerName(),
                refreshTokenCookie.issueHeader(result.refreshToken(), true));
        return ApiResponse.success(result.body());
    }

    /**
     * Google OAuth — 로그인 / 신규 가입 / 게스트 → 정식 전환.
     * 신규 가입은 201, 그 외(로그인·전환)는 200.
     * refresh token 은 HttpOnly Cookie 로 발급 (Max-Age = 30일).
     */
    @PostMapping("/google")
    public ApiResponse<GoogleAuthResponse> google(@AuthenticationPrincipal Long currentUserId,
                                                  @RequestBody @Valid GoogleAuthRequest request,
                                                  HttpServletResponse response) {
        GoogleAuthResult result = authService.googleAuth(
                request.idToken(), request.termsAgreements(), currentUserId);

        // /google 응답은 항상 정식 user (isGuest=false). 익명 → 정식 전환 케이스도 동일.
        response.addHeader(refreshTokenCookie.headerName(),
                refreshTokenCookie.issueHeader(result.refreshToken(), false));
        response.setStatus(result.newUser() ? HttpStatus.CREATED.value() : HttpStatus.OK.value());

        return ApiResponse.success(result.body());
    }

    /**
     * refresh token 으로 access token 재발급. refresh token 은 cookie 에서 추출 + rotation.
     * 익명/정식 여부는 토큰 자체 claim 으로 판별 → cookie 도 동일 만료기간으로 재발급.
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshTokenString = refreshTokenCookie.extract(request);
        TokenResponse tokens = authService.refresh(refreshTokenString);
        response.addHeader(refreshTokenCookie.headerName(),
                refreshTokenCookie.issueHeader(tokens.refreshToken(), tokens.isGuest()));
        return ApiResponse.success(Map.of("access_token", tokens.accessToken()));
    }

    /**
     * 로그아웃. 서버측 refresh token row 삭제 + cookie 만료.
     * 인증 필요(access_token 으로 user 식별).
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Long currentUserId, HttpServletResponse response) {
        if (currentUserId != null) {
            authService.logout(currentUserId);
        }
        response.addHeader(refreshTokenCookie.headerName(), refreshTokenCookie.expireHeader());
    }
}
