package com.example.tonefitserver.domain.reply.dto;

import com.example.tonefitserver.core.enums.Receiver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 회신 작성 호출 요청 (FUNC-Rep-07). 파악 응답((가)~(다))을 그대로 회송하고,
 * 사용자가 확정한 받는 사람 유형과 질문별 답변을 더해 보낸다.
 *
 * <p>생성과 달리 {@code purpose}(목적)는 받지 않는다 — 답장의 입장(수락/거절 등)은
 * 질문별 답변에서 AI 가 읽는다 (FUNC-Rep-06 / FUNC-Prp-04).
 *
 * <p>질문이 없으면 {@code answers} 는 비우고 {@code freeInput} 자유 입력 사용.
 * 길이 상한은 임시값 — PM 확정 후 조정.
 *
 * <p>{@code originalSubject} 는 "Re: [원제목]" 유지용 — FE 가 원 메일 제목을 보내면 사용하고,
 * 없으면(null) 모델이 회신 핵심 건명으로 새 제목을 짓는다. {@code 보내는 사람(나)} 이름은
 * 요청에 받지 않고 BE 가 인증 사용자 nickname 으로 채운다.
 */
public record ReplyDraftRequest(
        /* 파악 응답의 정리된 대화 회송 — summary_lines 미회송 시(1건·요약 실패) 작성 입력이자 항상 fallback */
        @NotBlank(message = "대화 내용은 필수입니다.")
        @Size(max = 12_000, message = "대화 내용은 최대 12,000자입니다.")
        String conversation,

        /* 요약 회송 (선택, PM 확정 2026-07) — 메일 2건 이상 + 요약 성공 시 FE 가 요약 응답(summary_lines)을
           그대로 되보낸다. 있으면 작성·점검의 대화 입력을 원문 대신 요약으로 대체(속도 개선),
           없으면 conversation 원문 사용 — 요약 실패·미도착이 작성을 막지 않는다. */
        @Size(max = 3, message = "요약은 최대 3줄입니다.")
        List<@Size(max = 300, message = "요약 항목은 최대 300자입니다.") String> summaryLines,

        /* 사용자가 확정한 받는 사람 유형 */
        @NotNull Receiver receiverType,

        /* 원 메일 제목 (선택). 없으면 모델이 새 제목 생성 */
        @Size(max = 255) String originalSubject,

        /* 파악 응답의 질문 목록 회송 (없었으면 빈 배열) */
        @Size(max = 20) @Valid List<Question> questions,

        /* 질문별 답변 — question_id 로 매핑 */
        @Size(max = 20) @Valid List<Answer> answers,

        /* 질문이 없을 때 자유 입력 — "사용자가 전하려는 내용" (FUNC-Rep-07) */
        @Size(max = 1_000) String freeInput,

        /* 그 밖에 전하고 싶은 말 (선택) — 질문 유무와 무관하게 항상 옵션 */
        @Size(max = 1_000) String extraMessage
) {
    /**
     * 파악 응답의 질문 회송 — 필드명은 파악 응답({@code {id, question, mail_order}})과 동일해야
     * FE 가 응답을 그대로 되보낼 수 있다(FUNC-Rep-07). 구 필드명 {@code text} 는 회송 계약 불일치로
     * 400("공백일 수 없습니다")을 유발해 {@code question} 으로 정정. {@code mailOrder} 는 회송 허용용(작성 미사용).
     */
    public record Question(int id,
                           @NotBlank(message = "질문 내용은 필수입니다.")
                           @Size(max = 500) String question,
                           Integer mailOrder) {
    }

    /**
     * 질문별 답변. {@code answer} 는 빈 값 허용 — 사용자가 답하지 않은 질문(미답변)은 빈/누락으로 오며,
     * 서비스가 그대로 전달하면 AI 가 중립으로 작성한다 (FUNC-Rep-06). 따라서 @NotBlank 를 걸지 않는다.
     */
    public record Answer(int questionId, @Size(max = 1_000) String answer) {
    }
}
