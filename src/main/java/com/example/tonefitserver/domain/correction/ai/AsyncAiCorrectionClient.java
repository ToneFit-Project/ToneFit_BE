package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Range;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 교정 AI 호출의 비동기 변형 — 폴오버 헤지용. Gemini·OpenAI 클라이언트가 구현하고,
 * FailoverAiCorrectionClient(Phase 3b) 가 둘을 race 시킨다.
 */
public interface AsyncAiCorrectionClient {

    CompletableFuture<AiCorrectionResult> correctAsync(String promptContent,
                                                       Receiver receiver,
                                                       String original,
                                                       List<Range> protectedRanges);
}
