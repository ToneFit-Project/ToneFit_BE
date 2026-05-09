package com.example.tonefitserver.core.dto.auth;

import com.example.tonefitserver.core.enums.CareerLevel;
import com.example.tonefitserver.core.enums.Industry;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청.
 *
 * <p>약관 동의는 MVP 단계에서 받지 않는다 (PM 결정).
 * 회원가입과 무관하게 동의 받아야 하는 약관이 별도로 있어 수집 시점이 재설계되는 동안 가입 폼에서는 제외.
 * 테이블/엔티티/repository 는 유지되어 있으니 향후 별도 엔드포인트로 동의를 수집하면 된다.
 */
public record SignupRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 254, message = "이메일은 최대 254자입니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 최소 8자 이상이어야 합니다.")
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 64, message = "닉네임은 최대 64자입니다.")
        String nickname,

        // 선택 입력
        Industry industry,
        CareerLevel careerLevel
) {
}
