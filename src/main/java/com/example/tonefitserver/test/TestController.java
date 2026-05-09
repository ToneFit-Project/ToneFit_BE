package com.example.tonefitserver.test;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.enums.ErrorType;
import com.example.tonefitserver.core.exception.BusinessException;
import com.example.tonefitserver.core.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발 편의용 엔드포인트. 운영(prod) 프로파일에서는 빈 등록되지 않아 404 로 응답한다.
 */
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
@Profile({"local", "docker"})
public class TestController {

    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping("/success")
    public ApiResponse<String> testSuccess() {
        return ApiResponse.success("Hello Tonefit!");
    }

    @GetMapping("/error")
    public ApiResponse<Void> testError() {
        throw new BusinessException(ErrorType.INVALID_REQUEST);
    }

    @GetMapping("/token")
    public ApiResponse<String> testToken(@RequestParam(defaultValue = "1") Long userId,
                                         @RequestParam(defaultValue = "false") boolean isGuest) {
        return ApiResponse.success(jwtTokenProvider.createAccessToken(userId, isGuest));
    }

    @GetMapping("/secure")
    public ApiResponse<String> testSecure() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ApiResponse.success("Secured access success! User: " + auth.getName());
    }
}
