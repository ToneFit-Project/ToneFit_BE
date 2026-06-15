package com.example.tonefitserver.domain.reply.ai;

import com.example.tonefitserver.core.enums.Receiver;

import java.util.List;

/**
 * 파악 호출 AI 결과 (FUNC-Rep-07 (나)·(다) + 사전 점검).
 *
 * <p>{@code status} 가 OK 가 아니면 recipient 는 null, questions 는 빈 목록 — 서비스가
 * EMPTY_THREAD / NOT_KOREAN 에러로 변환한다(FUNC-Rep-14).
 *
 * <p>conversation 은 결과에 없다 — BE 가 조립한 값을 그대로 응답에 쓰므로 모델이 재출력하지 않는다.
 * questions 의 id 는 서비스가 1부터 부여한다(모델은 질문·mailOrder 만 낸다).
 */
public record AiReplyAnalysisResult(Status status, Recipient recipient, List<Question> questions) {

    public enum Status { OK, EMPTY_THREAD, NOT_KOREAN }

    /** type 은 구현체가 모델의 RCP 코드를 Receiver 로 변환한 값. */
    public record Recipient(Receiver type, String label, String confidence, String reason) {
    }

    public record Question(String question, int mailOrder) {
    }
}
