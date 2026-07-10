package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Range;

import java.util.List;

/**
 * 교정 AI 호출 인터페이스. v0.5 부터 단일 메서드 ({@link #correct}) 만 유지.
 * 후교정/구조교정 인터페이스는 흐름 자체가 제거돼 사라짐.
 * purpose 는 PM 테스트 결과 품질 영향이 없어 입력에서 제거(2026-07).
 */
public interface AiCorrectionClient {

    AiCorrectionResult correct(String promptContent,
                               Receiver receiver,
                               String original,
                               List<Range> protectedRanges);
}
