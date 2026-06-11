package com.example.tonefitserver.domain.reply.ai;

import java.util.List;

/**
 * 내부 점검 결과 (FUNC-Rep-11) — 초안을 고치지 않고 확인만 한 평가.
 *
 * <p>사용자에게 노출하지 않는다(서버 안에서만). {@code detail} 은 메일 내용이 섞일 수 있어
 * 재작성 프롬프트로만 흘리고 <b>영속·로깅 금지</b> — 메타데이터·로그에는 type enum 만 남긴다.
 */
public record AiReplyInspection(boolean passed, List<Issue> issues) {

    /** 루브릭 매핑: (1) 완답성 (2) 지어내기 (3) 격식·방향·형식. */
    public enum IssueType {
        /** 물어본 것에 답하지 않음 — questionId 로 어느 질문인지 특정 */
        UNANSWERED_QUESTION,
        /** 대화·답변에 없는 사실·일정·약속을 지어냄 (FUNC-Rep-08) */
        FABRICATION,
        /** 수신자 유형 대비 격식(높임말 수위) 불일치 */
        FORMALITY_MISMATCH,
        /** 사용자 답변의 입장(수락/거절 등)과 초안 톤 불일치 */
        STANCE_MISMATCH,
        /** 이메일 형식 문제 (제목 부적절, 구조 누락 등) */
        FORMAT
    }

    public record Issue(IssueType type, Integer questionId, String detail) {
    }
}
