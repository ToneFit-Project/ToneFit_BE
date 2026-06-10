package com.example.tonefitserver.domain.reply.ai;

/**
 * 작성 호출 AI 결과 — 회신 초안 제목·본문 (FUNC-Rep-09).
 */
public record AiReplyDraftResult(
        String generatedSubject,
        String generatedEmail
) {
}
