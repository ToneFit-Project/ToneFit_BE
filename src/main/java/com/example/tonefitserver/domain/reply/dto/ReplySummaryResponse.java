package com.example.tonefitserver.domain.reply.dto;

import java.util.List;

/**
 * 회신 요약 호출 응답 — 화면 표시 전용 (PM 재설계 v0.58).
 *
 * <p>요약은 생성 파이프라인(파악→작성)에서 분리됐다. FE 가 "회신 준비" 버튼에서 요약·파악을
 * 병렬 호출하고, 요약이 먼저 도착하면 사용자에게 대화 맥락을 보여주는 용도로만 쓴다.
 * 파악·작성은 요약본이 아니라 정리된 원문 대화를 사용한다.
 *
 * <p>받은 메일은 제3자 글 — 처리 후 즉시 폐기, 저장·로깅 금지 (FUNC-Rep-13).
 */
public record ReplySummaryResponse(
        List<MailSummary> summaries
) {
    /**
     * @param order   메일 순번(1부터, 오래된 → 최신)
     * @param sender  보낸 사람(표시용). 미상이면 "(미상)"
     * @param summary 메일별 요약문
     */
    public record MailSummary(int order, String sender, String summary) {
    }
}
