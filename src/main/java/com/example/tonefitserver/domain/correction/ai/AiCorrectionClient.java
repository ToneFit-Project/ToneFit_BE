package com.example.tonefitserver.domain.correction.ai;

import com.example.tonefitserver.domain.session.Purpose;
import com.example.tonefitserver.domain.session.Range;
import com.example.tonefitserver.domain.session.Receiver;

import java.util.List;

public interface AiCorrectionClient {

    AiCorrectionResult correct(String promptContent,
                               Receiver receiver,
                               Purpose purpose,
                               String original,
                               List<Range> protectedRanges);

    AiFinalizeResult finalizePolish(String promptContent,
                                    Receiver receiver,
                                    Purpose purpose,
                                    String mergedText,
                                    List<Range> protectedRanges);

    /**
     * 구조 교정. 본문 구조(문장 순서·결합·분할 등) 변경 결과를 단일 텍스트로 반환.
     * protected_ranges 는 좌표 의미 잃으므로 받지 않음.
     */
    AiStructureResult correctStructure(String promptContent,
                                       Receiver receiver,
                                       Purpose purpose,
                                       String original);
}
