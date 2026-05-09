package com.example.tonefitserver.domain.auth;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.dto.auth.AnonymousResponse;
import com.example.tonefitserver.core.dto.auth.AuthResponse;
import com.example.tonefitserver.core.dto.auth.LoginRequest;
import com.example.tonefitserver.core.dto.auth.LoginResponse;
import com.example.tonefitserver.core.dto.auth.ReissueRequest;
import com.example.tonefitserver.core.dto.auth.SignupRequest;
import com.example.tonefitserver.core.dto.auth.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 신규 가입 또는 익명 → 정식 전환.
     * 익명 access_token 으로 인증된 상태에서 호출하면 동일 user.id 를 정식으로 승격.
     * 토큰 없이 호출하면 새 user 생성. (auth/** 는 SecurityConfig 에서 permitAll 이지만
     * JwtAuthenticationFilter 는 토큰이 있을 때만 principal 을 채움.)
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AuthResponse> signup(@AuthenticationPrincipal Long currentUserId,
                                            @RequestBody @Valid SignupRequest request) {
        return ApiResponse.success(authService.signup(request, currentUserId));
    }

    @PostMapping("/anonymous")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AnonymousResponse> anonymous() {
        return ApiResponse.success(authService.anonymous());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody @Valid ReissueRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }
}
