package com.example.tonefitserver.domain.reply.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 회신 파악 호출 요청 (FUNC-Rep-03). 사용자가 회신 폼에서 ToneFit 버튼을 누르면 FE 가
 * 받은 메일 대화 최근 3건(따라붙은 인용 제외)과 지금 회신의 To/CC 를 보낸다.
 *
 * <p>{@code mails} 는 시간순(오래된 → 최신). <b>마지막 요소가 사용자가 "답장"을 누른 메일</b>이며
 * 격식 판단 기준이 된다(FUNC-Rep-05).
 *
 * <p>받은 메일은 제3자 글 — 본문·보낸 사람 정보 모두 처리 후 즉시 폐기, 저장·로깅 금지 (FUNC-Rep-13).
 *
 * <p>길이 상한은 임시값 — "너무 김" 기준 PM 확정 후 조정.
 */
public record ReplyAnalysisRequest(
        @NotEmpty(message = "받은 메일 대화는 1건 이상이어야 합니다.")
        @Size(max = 3, message = "받은 메일 대화는 최근 3건까지입니다.")
        @Valid List<Mail> mails,

        @Size(max = 20) List<@Size(max = 255) String> to,
        @Size(max = 20) List<@Size(max = 255) String> cc
) {
    public record Mail(
            @Size(max = 255) String sender,
            @NotBlank(message = "메일 본문은 필수입니다.")
            @Size(max = 10_000, message = "메일 본문은 최대 10,000자입니다.")
            String body
    ) {
    }
}
