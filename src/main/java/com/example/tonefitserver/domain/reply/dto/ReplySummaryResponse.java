package com.example.tonefitserver.domain.reply.dto;

import java.util.List;

/**
 * 회신 요약 호출 응답 — 화면 표시 전용 (PM 재설계 v0.58, 요약 프롬프트 갱신본 2026-07).
 *
 * <p>메일별 요약에서 <b>대화 전체 요약</b>으로 변경: 최대 3줄(내용이 적으면 1~2줄),
 * 각 줄은 한 요점이며 줄 안에 줄바꿈이 없다. FE 는 순서대로 나열해 표시만 한다.
 *
 * <p>요약은 생성 파이프라인(파악→작성)에서 분리됐다. FE 가 "회신 준비" 버튼에서 요약·파악을
 * 병렬 호출하고, 요약이 먼저 도착하면 사용자에게 대화 맥락을 보여주는 용도로만 쓴다.
 * 파악·작성은 요약본이 아니라 정리된 원문 대화를 사용한다.
 *
 * <p>받은 메일은 제3자 글 — 처리 후 즉시 폐기, 저장·로깅 금지 (FUNC-Rep-13).
 */
public record ReplySummaryResponse(
        List<String> summaryLines
) {
}
