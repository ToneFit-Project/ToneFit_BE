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
}
