package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.core.enums.Receiver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 교정 요청. purpose(목적)는 PM 테스트 결과 품질 영향이 없어 입력에서 제거(2026-07) — 생성만 사용. */
public record CorrectionRequest(
        @NotNull Receiver receiverType,
        // FUNC-LLM-07: FE 1차 검증과 별개로 BE 방어선. 원문 최소 10자, 최대 2,000자.
        @NotBlank(message = "원문은 필수입니다.")
        @Size(min = 10, max = 2000, message = "원문은 최소 10자, 최대 2,000자입니다.")
        String originalEmail,
        List<ProtectedRange> protectedRanges
) {
    public record ProtectedRange(int start, int end) {
    }
}
