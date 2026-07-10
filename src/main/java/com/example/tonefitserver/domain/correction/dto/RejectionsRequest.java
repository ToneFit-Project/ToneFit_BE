package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Label;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 거절 교정 항목 보존 요청 (FUNC-Cor-02/06). FE 가 사용자의 개별 거부 결과를 모아 전송한다.
 *
 * <p>BE 는 AI_LEARNING(서비스 개선 분석) 동의자에 한해서만 저장한다(미동의 시 무시). 측정 이벤트는
 * 발화하지 않는다 — REJECTION_CLICKED 는 클라이언트 동작이라 FE 가 직접 Amplitude 로 측정(FUNC-Amp-03).
 *
 * <p>거부는 사유 입력 없이 처리되며, 항목 단위 '의미 훼손 의심' 플래그(예/아니오)만 선택적으로 받는다.
 * {@code meaningDamageSuspected} 는 사용자가 표시한 경우에만 값이 오고, 미표시면 null.
 *
 * <p>{@code receiverType} 은 해당 교정 요청 단위로 동일하므로 한 번만 받는다.
 * purpose 는 교정 입력에서 제거되어(2026-07) 회송도 없다 — 과거 보존 row 의 purpose 값만 남는다.
 */
public record RejectionsRequest(
        @NotNull Receiver receiverType,
        @NotEmpty(message = "거절 항목은 1개 이상이어야 합니다.")
        @Valid List<Item> items
) {
    public record Item(
            @NotNull Label label,
            @NotBlank @Size(max = 2000) String originalPhrase,
            @NotBlank @Size(max = 2000) String correctedPhrase,
            Boolean meaningDamageSuspected
    ) {
    }
}
