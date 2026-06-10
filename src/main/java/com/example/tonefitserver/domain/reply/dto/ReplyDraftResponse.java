package com.example.tonefitserver.domain.reply.dto;

/**
 * 회신 작성 호출 응답 — 제목·본문 (생성과 같은 형태, FUNC-Rep-09).
 * 내부 점검(FUNC-Rep-11) 결과는 노출하지 않는다 — 서버 안에서만 돈다.
 */
public record ReplyDraftResponse(
        String generatedSubject,
        String generatedEmail
) {
}
