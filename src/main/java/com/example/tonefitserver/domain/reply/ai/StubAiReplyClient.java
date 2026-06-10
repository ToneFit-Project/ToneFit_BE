package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 로컬·테스트용 stub. 대화는 그대로 echo, 수신자 추측은 DIRECT_SUPERVISOR 고정,
 * 질문은 더미 1개 — FE 가 입력 화면(R-01) 흐름을 끝까지 태울 수 있게.
 */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubAiReplyClient implements AiReplyClient {

    @Override
    public AiReplyAnalysisResult analyze(String promptContent, List<String> mailBodies,
                                         List<String> to, List<String> cc) {
        String joined = String.join("\n---\n", mailBodies);
        return new AiReplyAnalysisResult(
                joined,
                Receiver.DIRECT_SUPERVISOR,
                List.of("[테스트] 받은 메일의 요청에 어떻게 답하시겠습니까?")
        );
    }

    @Override
    public AiReplyDraftResult draft(String promptContent, Receiver receiver, String conversation,
                                    List<QuestionAnswer> questionAnswers, String freeInput) {
        String firstAnswer = questionAnswers.isEmpty()
                ? (freeInput == null ? "" : freeInput)
                : questionAnswers.get(0).answer();
        return new AiReplyDraftResult(
                "[테스트] 회신: " + (receiver == null ? "메일" : receiver.name()),
                "안녕하세요.\n\n" + firstAnswer + "\n\n감사합니다."
        );
    }
}
