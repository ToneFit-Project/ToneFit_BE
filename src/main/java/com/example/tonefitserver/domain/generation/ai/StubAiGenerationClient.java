package com.example.tonefitserver.domain.generation.ai;

import com.example.tonefitserver.core.enums.Purpose;
import com.example.tonefitserver.core.enums.Receiver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로컬·테스트용 stub. brief_content 를 본문에 그대로 echo, 제목은 고정 문구.
 */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubAiGenerationClient implements AiGenerationClient {

    @Override
    public AiGenerationResult generate(String promptContent, Receiver receiver, Purpose purpose, String briefContent) {
        return new AiGenerationResult(
                "[테스트] " + (purpose == null ? "메일" : purpose.name()),
                briefContent == null ? "" : briefContent
        );
    }
}
