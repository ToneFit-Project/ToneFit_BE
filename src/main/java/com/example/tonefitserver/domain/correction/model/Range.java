package com.example.tonefitserver.domain.correction.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 보호 구간(start~end) 값 객체. 교정 요청 시 AI 가 수정하면 안 되는 구간을 표시하는 데만 쓰인다.
 * 더 이상 영속화되지 않는다(교정 세션 제거, V17) — AI 호출 경로의 in-memory 값.
 * (구 {@code domain.session.Range})
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Range {
    private int start;
    private int end;
}
