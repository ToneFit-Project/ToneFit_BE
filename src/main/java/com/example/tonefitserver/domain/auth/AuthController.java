package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.dto.auth.GoogleAuthRequest;
import com.example.tonefitserver.core.dto.auth.GoogleAuthResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 컨트롤러. Google OAuth 단일 진입.
 * 익명 토큰·자체 refresh token·로그아웃 API 는 모두 제거됨.
 *
 * <ul>
 *   <li>웹 데모: 토큰 없이 {@code /generations} 직접 호출</li>
 *   <li>Extension: {@code /auth/google} 로 로그인. access token 만료 시 chrome.identity silent refresh 로
 *       새 Google ID token 을 받아 {@code /auth/google} 재호출 → 새 access token (자체 refresh 불필요)</li>
 *   <li>로그아웃: 서버 stateless — FE 가 access token 폐기 + chrome.identity 캐시 제거로 처리(서버 API 없음)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Google OAuth — 로그인 / 신규 가입. 신규 가입은 201, 기존 로그인은 200.
     * access token 은 응답 body 로 발급 (refresh token 없음).
     */
    @PostMapping("/google")
    public ApiResponse<GoogleAuthResponse> google(@RequestBody @Valid GoogleAuthRequest request,
                                                  HttpServletResponse response) {
        GoogleAuthResult result = authService.googleAuth(request.idToken(), request.termsAgreements());
        response.setStatus(result.newUser() ? HttpStatus.CREATED.value() : HttpStatus.OK.value());
        return ApiResponse.success(result.body());
    }
}
