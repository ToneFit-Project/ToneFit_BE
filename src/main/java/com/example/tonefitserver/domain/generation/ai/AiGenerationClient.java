package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Receiver;

/**
 * 생성(Generation) AI 호출 인터페이스. v0.52 API 명세 §4.
 *
 * <p>입력: receiver/purpose + brief_content. 출력: 제목 + 본문.
 * 호출은 일회성이며 BE 측에서는 결과를 저장하지 않는다 (체험 횟수는 FE/localStorage 관리).
 */
public interface AiGenerationClient {

    AiGenerationResult generate(String promptContent,
                                Receiver receiver,
                                Purpose purpose,
                                String briefContent);
}
