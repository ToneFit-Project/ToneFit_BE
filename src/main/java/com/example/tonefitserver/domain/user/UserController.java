package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.dto.user.TermsStatusResponse;
import com.example.tonefitserver.core.dto.user.UserResponse;
import com.example.tonefitserver.core.enums.TermsType;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * v0.5 기준. PM 요구사항 REQ-Agree FUNC-Ag-06/07: 선택 동의 토글(철회/재동의) 엔드포인트.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> getMe(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(userService.getMe(userId));
    }

    /**
     * 약관 동의 현황 — 설정 화면용. PATCH /me/terms/{type} 의 조회 짝.
     * 7종 전체를 항상 반환 (기록 없는 타입은 agreed=false).
     */
    @GetMapping("/me/terms")
    public ApiResponse<TermsStatusResponse> getTerms(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(userService.getTerms(userId));
    }

    /**
     * 선택 약관 토글 (MARKETING / AI_LEARNING).
     * 필수 약관(SERVICE / PRIVACY)에 대해 호출하면 400.
     */
    @PatchMapping("/me/terms/{type}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleTerms(@AuthenticationPrincipal Long userId,
                            @PathVariable TermsType type,
                            @RequestBody @NotNull TermsToggleRequest request) {
        userService.toggleTerms(userId, type, request.agreed());
    }

    public record TermsToggleRequest(@NotNull Boolean agreed) {
    }
}
