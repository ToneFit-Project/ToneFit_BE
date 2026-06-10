package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;

import java.util.List;

/**
 * 회신(Reply) AI 호출 인터페이스. REQ-Reply FUNC-Rep-02 의 두 호출 경계에 대응한다.
 *
 * <ul>
 *   <li>{@link #analyze} — ②요약+③파악 (light 모델, FUNC-Rep-15). 인용·서명 제거(①)는
 *       기계적 정리라 서비스 코드 몫.</li>
 *   <li>{@link #draft} — ⑤작성 (main 모델). ⑥내부 점검(light 모델 judge)과 조건부 1회
 *       재작성 오케스트레이션은 서비스 몫 — Phase B 에서 점검 primitive 추가 예정.</li>
 * </ul>
 */
public interface AiReplyClient {

    /**
     * 정리된 메일 대화를 요약(길 때)하고 파악한다 — 받는 사람 유형 추측 + 답해야 할 질문 추출.
     *
     * @param promptContent 파악 단계 system prompt (수신자 무관, 코드 상수)
     * @param mailBodies    정리된 메일 본문들 — 시간순, 마지막이 답장 대상 메일
     * @param to            지금 회신의 받는 사람 (격식 판단 참고, FUNC-Rep-05)
     * @param cc            참조 — 윗사람·외부 상대 포함 시 격식 상향 신호
     */
    AiReplyAnalysisResult analyze(String promptContent, List<String> mailBodies,
                                  List<String> to, List<String> cc);

    /**
     * 회신 초안 작성. 답장의 입장(수락/거절 등)은 질문별 답변에서 읽는다 (FUNC-Rep-06).
     * 받은 메일 대화에 없는 사실·일정·약속을 만들어내지 않는다 (FUNC-Rep-08).
     *
     * @param promptContent 회신 작성 prompt (DB, REPLY × recipient — FUNC-Prp-04)
     * @param receiver      사용자가 확정한 받는 사람 유형
     * @param conversation  정리·요약된 대화 (파악 응답 회송분)
     * @param questionAnswers "질문: 답변" 페어 목록 (질문 없으면 빈 목록)
     * @param freeInput     질문이 없을 때 자유 입력 (nullable)
     */
    AiReplyDraftResult draft(String promptContent, Receiver receiver, String conversation,
                             List<QuestionAnswer> questionAnswers, String freeInput);

    /** 질문-답변 페어. 점검 단계가 id 로 완답성을 검사한다 ("2번 질문에 답 안 함"). */
    record QuestionAnswer(int id, String question, String answer) {
    }
}
