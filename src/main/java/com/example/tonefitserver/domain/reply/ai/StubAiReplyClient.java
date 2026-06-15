package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 로컬·테스트용 stub. 요약은 앞부분 잘라 echo, 파악은 DIRECT_SUPERVISOR + 더미 질문 1개,
 * 점검은 항상 통과 — FE 가 입력 화면(R-01) 흐름을 끝까지 태울 수 있게.
 */
@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubAiReplyClient implements AiReplyClient {

    @Override
    public List<String> summarize(List<String> mailBodies) {
        return mailBodies.stream()
                .map(b -> b.length() > 60 ? b.substring(0, 60) + "…" : b)
                .toList();
    }

    @Override
    public AiReplyAnalysisResult analyze(AnalyzeInput in) {
        return new AiReplyAnalysisResult(
                AiReplyAnalysisResult.Status.OK,
                new AiReplyAnalysisResult.Recipient(Receiver.DIRECT_SUPERVISOR, "상사", "mid", "[테스트] 추측 근거"),
                List.of(new AiReplyAnalysisResult.Question("[테스트] 받은 메일의 요청에 어떻게 답하시겠습니까?", 1))
        );
    }

    @Override
    public AiReplyDraftResult draft(DraftInput in) {
        String firstAnswer = in.questionAnswers().isEmpty()
                ? (in.freeInput() == null ? "" : in.freeInput())
                : (in.questionAnswers().get(0).answer() == null ? "" : in.questionAnswers().get(0).answer());
        String subject = in.originalSubject() != null && !in.originalSubject().isBlank()
                ? "Re: " + in.originalSubject()
                : "[테스트] 회신: " + (in.receiver() == null ? "메일" : in.receiver().name());
        return new AiReplyDraftResult(subject, "안녕하세요.\n\n" + firstAnswer + "\n\n감사합니다.");
    }

    @Override
    public AiReplyInspection inspect(InspectInput in) {
        return new AiReplyInspection(true, List.of());
    }
}
