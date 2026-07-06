package com.example.tonefitserver.core.dto.user;

import com.example.tonefitserver.core.enums.TermsType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 약관 동의 현황 — GET /users/me/terms. PATCH /users/me/terms/{type} 의 조회 짝.
 *
 * <p>7종 전체를 항상 내려준다(기록 없는 타입은 agreed=false) — FE 가 약관 목록을
 * 하드코딩하지 않고 이 응답만으로 설정 화면을 그릴 수 있도록.
 */
public record TermsStatusResponse(List<Item> terms) {

    public record Item(
            TermsType type,
            /* 필수 약관 여부 — true 면 PATCH 토글 불가(400) */
            boolean required,
            /* 활성 동의 보유 여부 (agreed=true AND revoked_at IS NULL, 버전 무관) */
            boolean agreed,
            /* 활성 동의의 약관 버전. 미동의면 null */
            String version,
            /* 동의 시각. 미동의면 null */
            LocalDateTime agreedAt
    ) {
    }
}
