package com.example.tonefitserver.core.dto.user;

import com.example.tonefitserver.core.enums.CareerLevel;
import com.example.tonefitserver.core.enums.Industry;
import jakarta.validation.constraints.Size;

/**
 * PATCH /users/me 요청. 보낼 필드만 채워서 보낸다.
 */
public record UpdateUserRequest(
        @Size(max = 64, message = "닉네임은 최대 64자입니다.")
        String nickname,
        Industry industry,
        CareerLevel careerLevel
) {
}
