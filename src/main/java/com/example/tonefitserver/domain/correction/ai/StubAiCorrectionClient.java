package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.core.enums.Receiver;
import com.example.tonefitserver.domain.correction.model.Range;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubAiCorrectionClient implements AiCorrectionClient {

    @Override
    public AiCorrectionResult correct(String promptContent, Receiver receiver,
                                      String original, List<Range> protectedRanges) {
        return new AiCorrectionResult(List.of());
    }
}
