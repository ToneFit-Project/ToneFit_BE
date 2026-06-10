package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;

import java.util.List;

/**
 * 파악 호출 AI 결과 (FUNC-Rep-07 의 (가)~(다)).
 *
 * <p>{@code conversation} 은 요약본(길 때) 또는 정리된 원문.
 * {@code questions} 의 id 는 서비스가 1부터 순번으로 재부여해 응답에 싣는다.
 */
public record AiReplyAnalysisResult(
        String conversation,
        Receiver receiverTypeSuggestion,
        List<String> questions
) {
}
