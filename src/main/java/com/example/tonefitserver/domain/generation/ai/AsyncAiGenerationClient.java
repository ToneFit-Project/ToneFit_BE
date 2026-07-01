package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;

import java.util.concurrent.CompletableFuture;

/**
 * 생성 AI 호출의 비동기 변형 — 폴오버 헤지(primary/fallback 병행 race)를 위해 필요.
 * Gemini·OpenAI 클라이언트가 구현하고, FailoverAiGenerationClient(Phase 3) 가 둘을 race 시킨다.
 * 동기 {@link AiGenerationClient} 는 서비스가 그대로 호출하며, 폴오버 시 데코레이터가 이 future 를 블로킹해 반환한다.
 */
public interface AsyncAiGenerationClient {

    CompletableFuture<AiGenerationResult> generateAsync(String promptContent,
                                                        Receiver receiver,
                                                        Purpose purpose,
                                                        String briefContent);
}
