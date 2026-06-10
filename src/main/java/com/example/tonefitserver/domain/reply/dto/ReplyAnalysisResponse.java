package com.example.tonefitserver.domain.reply.dto;

import com.example.tonefitserver.core.enums.Receiver;

import java.util.List;

/**
 * 회신 파악 호출 응답 (FUNC-Rep-07). 입력 화면(R-01)에 채울 파악 결과 — 위에서 아래 순서.
 *
 * <p>서버는 이 결과를 저장하지 않는다. FE 가 화면에 띄웠다가 사용자 확정값과 함께
 * 작성 호출({@link ReplyDraftRequest})로 그대로 회송한다 — 두 호출 사이 서버 상태 없음.
 *
 * <p>{@code questions[].id} 는 1부터 순번 — 작성 호출의 답변 매핑과 내부 점검
 * ("2번 질문에 답 안 함", FUNC-Rep-11)의 기준 식별자.
 */
public record ReplyAnalysisResponse(
        /* (가) 정리된 대화 내용 — 요약본(길 때) 또는 원문 */
        String conversation,
        /* (나) 받는 사람 유형 — AI 추측 기본값. 최종값은 사용자가 고름 (FUNC-Rep-05) */
        Receiver receiverTypeSuggestion,
        /* (다) 답해야 할 질문·요청 목록 — 받은 메일에서 추출. 없으면 빈 배열 */
        List<Question> questions
) {
    public record Question(int id, String text) {
    }
}
