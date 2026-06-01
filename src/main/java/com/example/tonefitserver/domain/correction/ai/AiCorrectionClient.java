package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Range;
import com.example.tonefitserver.domain.session.Receiver;

import java.util.List;

/**
 * 교정 AI 호출 인터페이스. v0.5 부터 단일 메서드 ({@link #correct}) 만 유지.
 * 후교정/구조교정 인터페이스는 흐름 자체가 제거돼 사라짐.
 * (생성 기능은 Phase 3 에서 별도 메서드 또는 별도 client 로 추가)
 */
public interface AiCorrectionClient {

    AiCorrectionResult correct(String promptContent,
                               Receiver receiver,
                               Purpose purpose,
                               String original,
                               List<Range> protectedRanges);
}
