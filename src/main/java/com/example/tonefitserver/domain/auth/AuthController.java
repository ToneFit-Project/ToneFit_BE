package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.ApiResponse;
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

/**
 * 인증 컨트롤러. 익명 토큰 폐지로 {@code /anonymous} 는 제거됨 — 웹 데모는 토큰 없이
 * {@code /generations} 를 직접 호출한다. 정식 로그인은 Google OAuth({@code /google}) 단일 진입.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    /**
     * Google OAuth — 로그인 / 신규 가입. 신규 가입은 201, 기존 로그인은 200.
     * refresh token 은 HttpOnly Cookie 로 발급 (Max-Age = 30일).
     */
    @PostMapping("/google")
    public ApiResponse<GoogleAuthResponse> google(@RequestBody @Valid GoogleAuthRequest request,
                                                  HttpServletResponse response) {
        GoogleAuthResult result = authService.googleAuth(request.idToken(), request.termsAgreements());

        response.addHeader(refreshTokenCookie.headerName(),
                refreshTokenCookie.issueHeader(result.refreshToken(), false));
        response.setStatus(result.newUser() ? HttpStatus.CREATED.value() : HttpStatus.OK.value());

        return ApiResponse.success(result.body());
    }

    /**
     * refresh token 으로 access token 재발급. refresh token 은 cookie 에서 추출 + rotation.
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshTokenString = refreshTokenCookie.extract(request);
        TokenResponse tokens = authService.refresh(refreshTokenString);
        response.addHeader(refreshTokenCookie.headerName(),
                refreshTokenCookie.issueHeader(tokens.refreshToken(), false));
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
