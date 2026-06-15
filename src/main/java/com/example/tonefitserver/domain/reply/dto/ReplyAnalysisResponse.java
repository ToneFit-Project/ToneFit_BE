package com.example.tonefitserver.domain.reply.dto;

import com.example.tonefitserver.core.enums.Receiver;

import java.util.List;

/**
 * 회신 파악 호출 응답 (FUNC-Rep-07). 입력 화면(R-01)에 채울 파악 결과.
 *
 * <p>서버는 이 결과를 저장하지 않는다. FE 가 화면에 띄웠다가 사용자 확정값과 함께
 * 작성 호출({@link ReplyDraftRequest})로 그대로 회송한다 — 두 호출 사이 서버 상태 없음.
 *
 * <p>{@code conversation} 은 BE 가 조립한다(정리 원문 + 길면 요약본) — 파악 모델이 대화를
 * 재출력하지 않아 토큰을 아낀다.
 *
 * <p>사전 점검(빈 대화·한국어 아님)은 200 으로 내려오지 않는다 — 각각 {@code EMPTY_THREAD}·
 * {@code NOT_KOREAN} 에러로 응답하므로 이 응답은 항상 정상 케이스다.
 *
 * <p>{@code recipient.confidence}·{@code label}·{@code reason}, {@code questions[].mailOrder} 는
 * 현재 소비처 없이 출력만 한다(추후 활용 여지).
 */
public record ReplyAnalysisResponse(
        String conversation,
        Recipient recipient,
        List<Question> questions
) {
    /**
     * @param type       수신자 유형 추측(기본값, 사용자가 변경 가능). 모델의 RCP 코드를 Receiver 로 변환
     * @param label      한글 라벨("상사" 등)
     * @param confidence 추측 확신도 high/mid/low (FUNC-Rep 파악 — 현재 미소비)
     * @param reason     추측 근거 한 줄
     */
    public record Recipient(Receiver type, String label, String confidence, String reason) {
    }

    /**
     * @param id        서버 부여 순번(1부터) — 작성 호출 답변 매핑·내부 점검 기준
     * @param question  답해야 할 질문·요청
     * @param mailOrder 그 질문이 나온 메일 번호 (현재 미소비)
     */
    public record Question(int id, String question, int mailOrder) {
    }
}
