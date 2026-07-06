package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;

import java.util.List;

/**
 * 회신(Reply) AI 호출 인터페이스. REQ-Reply FUNC-Rep-02 의 단계에 대응한다.
 * 요약·파악·점검은 저가(light) 모델, 작성은 메인 모델 (FUNC-Rep-15) — 구현체가 모델을 선택한다.
 *
 * <ul>
 *   <li>{@link #summarize} — ② 요약 (이전 메일이 길 때만 호출, 별도 단계). PM 요약 프롬프트.</li>
 *   <li>{@link #analyze} — ③ 파악: 받는 사람 유형 추측 + 답할 질문 추출 + 사전 점검(status).</li>
 *   <li>{@link #draft} — ⑤ 작성. 재작성 시 {@code revisionNotes} 전달.</li>
 *   <li>{@link #inspect} — ⑥ 내부 점검 (judge). 점검→조건부 1회 재작성 오케스트레이션은 서비스 몫.</li>
 * </ul>
 *
 * <p>인용·서명 제거(①)는 기계적 정리라 서비스의 {@code MailCleaner} 몫(AI 아님).
 */
public interface AiReplyClient {

    /**
     * 이전 메일들을 각각 요약한다(회신에서 답할 단서는 보존). 입력 순서와 동일 순서로 반환.
     * @param mailBodies 요약할 메일 본문들 (보통 답장 대상 제외한 이전 메일)
     */
    List<String> summarize(List<String> mailBodies);

    /** ③ 파악 — 받는 사람 유형 추측 + 답할 질문 추출 + 사전 점검. */
    AiReplyAnalysisResult analyze(AnalyzeInput in);

    /** ⑤ 작성 — 회신 초안 제목·본문. */
    AiReplyDraftResult draft(DraftInput in);

    /** ⑥ 내부 점검 — 완답성·지어내기·격식/방향/형식. 고치지 않고 판정만. */
    AiReplyInspection inspect(InspectInput in);

    /**
     * 파악 입력.
     * @param meIdentity        보내는 사람(나) 식별 — 이름/이메일 (도메인 비교용)
     * @param replyTargetSender 답장 대상 메일 발신자 (격식 기준)
     * @param to                받는 사람(To)
     * @param cc                참조(CC) — 윗사람·외부 포함 시 격식 상향
     * @param conversation      BE 가 조립한 대화 텍스트([1] 보낸사람/본문 블록) — 정리 원문 그대로.
     *                          요약은 표시 전용으로 분리(v0.58), 파악 경로에 섞이지 않는다
     */
    record AnalyzeInput(String meIdentity, String replyTargetSender,
                        List<String> to, List<String> cc, String conversation) {
    }

    /**
     * 작성 입력.
     * @param promptContent  DB REPLY×recipient prompt (없으면 구현체 기본값)
     * @param receiver       사용자가 확정한 받는 사람 유형
     * @param senderName     보내는 사람(나) 이름 — 인증 사용자 nickname. 없으면 null
     * @param originalSubject 원 메일 제목 — 없으면 null(모델이 새 제목 생성)
     * @param conversation   정리·요약된 대화 (회송분)
     * @param questionAnswers "질문: 답변" 페어 (질문 없으면 빈 목록)
     * @param freeInput      질문이 없을 때 자유 입력 (nullable)
     * @param revisionNotes  재작성 시 점검 지적사항. 첫 작성은 빈 목록
     */
    record DraftInput(String promptContent, Receiver receiver, String senderName, String originalSubject,
                      String conversation, List<QuestionAnswer> questionAnswers, String freeInput,
                      String extraMessage, List<String> revisionNotes) {
    }

    /** 점검 입력. */
    record InspectInput(Receiver receiver, String conversation, List<QuestionAnswer> questionAnswers,
                        String freeInput, String extraMessage, AiReplyDraftResult draft) {
    }

    /** 질문-답변 페어. id 로 완답성을 검사한다 ("2번 질문에 답 안 함"). */
    record QuestionAnswer(int id, String question, String answer) {
    }
}
