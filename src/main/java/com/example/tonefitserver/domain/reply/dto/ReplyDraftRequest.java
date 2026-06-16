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
        /* 파악 응답의 정리된 대화 회송 */
        @NotBlank(message = "대화 내용은 필수입니다.")
        @Size(max = 12_000, message = "대화 내용은 최대 12,000자입니다.")
        String conversation,

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
    public record Question(int id, @NotBlank @Size(max = 500) String text) {
    }

    public record Answer(int questionId, @NotBlank @Size(max = 1_000) String answer) {
    }
}
