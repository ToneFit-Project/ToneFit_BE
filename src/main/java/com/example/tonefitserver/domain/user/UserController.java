package com.example.tonefitserver.domain.user;

import com.example.tonefitserver.core.dto.ApiResponse;
import com.example.tonefitserver.core.dto.user.UpdateUserRequest;
import com.example.tonefitserver.core.dto.user.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> getMe(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(userService.getMe(userId));
    }

    @PatchMapping("/me")
    public ApiResponse<UserResponse> updateMe(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.success(userService.updateMe(userId, request));
    }
}
