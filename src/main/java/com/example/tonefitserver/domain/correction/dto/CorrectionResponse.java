package com.example.tonefitserver.domain.correction.dto;

import com.example.tonefitserver.domain.correction.model.Label;

import java.util.List;

/**
 * 교정 응답 (FUNC-Cor-01/05). 세션·확정 흐름 제거(v0.6)로 sessionId·createdAt·action 미노출 —
 * 서버는 교정 결과를 저장하지 않으므로 응답은 무상태 교정 항목 목록뿐이다.
 *
 * <p>corrected_email 은 반환하지 않는다 — FE 가 원문 + changes 로 재조립.
 * 각 항목은 위치(start/end)·원문·교정문·이유·교정 계층(label)을 담는다.
 */
public record CorrectionResponse(
        List<ChangeItem> changes
) {
    public record ChangeItem(
            int index,
            int start,
            int end,
            String original,
            String corrected,
            String reason,
            Label label,
            double confidence,
            List<String> appliedRules
    ) {
    }
}
